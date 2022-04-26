package scaladex.infra

import java.time.Instant
import java.util.UUID

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.HikariDataSource
import doobie.implicits._
import scaladex.core.model.Artifact
import scaladex.core.model.ArtifactDependency
import scaladex.core.model.GithubInfo
import scaladex.core.model.GithubResponse
import scaladex.core.model.GithubStatus
import scaladex.core.model.Language
import scaladex.core.model.Platform
import scaladex.core.model.Project
import scaladex.core.model.ProjectDependency
import scaladex.core.model.ReleaseDependency
import scaladex.core.model.SemanticVersion
import scaladex.core.model.UserState
import scaladex.core.model.web.ArtifactsPageParams
import scaladex.core.service.SchedulerDatabase
import scaladex.infra.sql.ArtifactDependencyTable
import scaladex.infra.sql.ArtifactTable
import scaladex.infra.sql.DoobieUtils
import scaladex.infra.sql.GithubInfoTable
import scaladex.infra.sql.ProjectDependenciesTable
import scaladex.infra.sql.ProjectSettingsTable
import scaladex.infra.sql.ProjectTable
import scaladex.infra.sql.ReleaseDependenciesTable
import scaladex.infra.sql.ReleaseTable
import scaladex.infra.sql.UserSessionsTable

class SqlDatabase(datasource: HikariDataSource, xa: doobie.Transactor[IO]) extends SchedulerDatabase with LazyLogging {

  private val flyway = DoobieUtils.flyway(datasource)
  def migrate: IO[Unit] = IO(flyway.migrate())
  def dropTables: IO[Unit] = IO(flyway.clean())

  override def insertArtifact(
      artifact: Artifact,
      dependencies: Seq[ArtifactDependency],
      time: Instant
  ): Future[Boolean] = {
    val unknownStatus = GithubStatus.Unknown(time)
    for {
      isNewProject <- insertProjectRef(artifact.projectRef, unknownStatus)
      _ <- run(ArtifactTable.insertIfNotExist(artifact))
      _ <- run(ReleaseTable.insertIfNotExists.run(artifact.release))
      _ <- insertDependencies(dependencies)
    } yield isNewProject
  }

  override def insertProject(project: Project): Future[Unit] =
    for {
      updated <- insertProjectRef(project.reference, project.githubStatus)
      _ <-
        if (updated) {
          project.githubInfo
            .map(updateGithubInfoAndStatus(project.reference, _, project.githubStatus))
            .getOrElse(Future.successful(()))
            .flatMap(_ => updateProjectSettings(project.reference, project.settings))
        } else {
          logger.warn(s"${project.reference} already inserted")
          Future.successful(())
        }
    } yield ()

  override def insertArtifacts(artifacts: Seq[Artifact]): Future[Unit] =
    run(ArtifactTable.insertIfNotExist(artifacts)).map(_ => ())

  override def insertDependencies(dependencies: Seq[ArtifactDependency]): Future[Unit] =
    run(ArtifactDependencyTable.insertIfNotExist.updateMany(dependencies)).map(_ => ())

  // return true if inserted, false if it already existed
  private def insertProjectRef(ref: Project.Reference, status: GithubStatus): Future[Boolean] =
    run(ProjectTable.insertIfNotExists.run((ref, status))).map(x => x >= 1)

  override def getAllProjectsStatuses(): Future[Map[Project.Reference, GithubStatus]] =
    run(ProjectTable.selectReferenceAndStatus.to[Seq]).map(_.toMap)

  override def getAllProjects(): Future[Seq[Project]] =
    run(ProjectTable.selectProject.to[Seq])

  override def updateArtifacts(artifacts: Seq[Artifact], newRef: Project.Reference): Future[Int] = {
    val mavenReferences = artifacts.map(r => newRef -> r.mavenReference)
    run(ArtifactTable.updateProjectRef.updateMany(mavenReferences))
  }

  override def updateArtifactReleaseDate(reference: Artifact.MavenReference, releaseDate: Instant): Future[Int] =
    run(ArtifactTable.updateReleaseDate.run((releaseDate, reference)))

  override def updateGithubInfo(
      repo: Project.Reference,
      response: GithubResponse[(Project.Reference, GithubInfo)],
      now: Instant
  ): Future[Unit] =
    response match {
      case GithubResponse.Ok((_, info)) =>
        val status = GithubStatus.Ok(now)
        updateGithubInfoAndStatus(repo, info, status)

      case GithubResponse.MovedPermanently((destination, info)) =>
        val status = GithubStatus.Moved(now, destination)
        logger.info(s"$repo moved to $status")
        moveProject(repo, info, status)

      case GithubResponse.Failed(code, reason) =>
        val status =
          if (code == 404) GithubStatus.NotFound(now) else GithubStatus.Failed(now, code, reason)
        logger.info(s"Failed to download github info for $repo because of $status")
        updateGithubStatus(repo, status)
    }

  override def updateGithubInfoAndStatus(
      ref: Project.Reference,
      githubInfo: GithubInfo,
      githubStatus: GithubStatus
  ): Future[Unit] =
    for {
      _ <- updateGithubStatus(ref, githubStatus)
      _ <- run(GithubInfoTable.insertOrUpdate.run((ref, githubInfo, githubInfo)))
    } yield ()

  override def updateProjectSettings(ref: Project.Reference, settings: Project.Settings): Future[Unit] =
    run(ProjectSettingsTable.insertOrUpdate.run((ref, settings, settings))).map(_ => ())

  override def getProject(projectRef: Project.Reference): Future[Option[Project]] =
    run(ProjectTable.selectByReference.option(projectRef))

  override def getArtifacts(groupId: Artifact.GroupId, artifactId: Artifact.ArtifactId): Future[Seq[Artifact]] =
    run(ArtifactTable.selectArtifactByGroupIdAndArtifactId.to[Seq](groupId, artifactId))

  override def getArtifacts(projectRef: Project.Reference): Future[Seq[Artifact]] =
    run(ArtifactTable.selectArtifactByProject.to[Seq](projectRef))

  override def getDependencies(projectRef: Project.Reference): Future[Seq[ArtifactDependency]] =
    run(ArtifactDependencyTable.selectDependencyFromProject.to[Seq](projectRef))

  override def getFormerReferences(projectRef: Project.Reference): Future[Seq[Project.Reference]] =
    run(ProjectTable.selectByNewReference.to[Seq](projectRef))

  override def getArtifactsByName(ref: Project.Reference, artifactName: Artifact.Name): Future[Seq[Artifact]] =
    run(ArtifactTable.selectArtifactByProjectAndName.to[Seq]((ref, artifactName)))

  override def getArtifactsByVersion(ref: Project.Reference, version: SemanticVersion): Future[Seq[Artifact]] =
    run(ArtifactTable.selectArtifactByProjectAndVersion.to[Seq](ref, version))

  override def getArtifactNames(ref: Project.Reference): Future[Seq[Artifact.Name]] =
    run(ArtifactTable.selectArtifactName.to[Seq](ref))

  override def getArtifactPlatforms(ref: Project.Reference, artifactName: Artifact.Name): Future[Seq[Platform]] =
    run(ArtifactTable.selectPlatformByArtifactName.to[Seq]((ref, artifactName)))

  override def getArtifactByMavenReference(mavenRef: Artifact.MavenReference): Future[Option[Artifact]] =
    run(ArtifactTable.selectByMavenReference.option(mavenRef))

  override def getAllArtifacts(
      maybeLanguage: Option[Language],
      maybePlatform: Option[Platform]
  ): Future[Seq[Artifact]] =
    (maybeLanguage, maybePlatform) match {
      case (Some(language), Some(platform)) =>
        run(ArtifactTable.selectArtifactByLanguageAndPlatform.to[Seq](language, platform))
      case (Some(language), _) => run(ArtifactTable.selectArtifactByLanguage.to[Seq](language))
      case (_, Some(platform)) => run(ArtifactTable.selectArtifactByPlatform.to[Seq](platform))
      case _                   => run(ArtifactTable.selectAllArtifacts.to[Seq])
    }

  def countProjects(): Future[Long] =
    run(ProjectTable.countProjects.unique)

  override def countArtifacts(): Future[Long] =
    run(ArtifactTable.count.unique)

  def countDependencies(): Future[Long] =
    run(ArtifactDependencyTable.count.unique)

  override def getDirectDependencies(artifact: Artifact): Future[Seq[ArtifactDependency.Direct]] =
    run(ArtifactDependencyTable.selectDirectDependency.to[Seq](artifact.mavenReference))

  override def getReverseDependencies(artifact: Artifact): Future[Seq[ArtifactDependency.Reverse]] =
    run(ArtifactDependencyTable.selectReverseDependency.to[Seq](artifact.mavenReference))

  override def getArtifacts(
      ref: Project.Reference,
      artifactName: Artifact.Name,
      version: SemanticVersion
  ): Future[Seq[Artifact]] =
    run(ArtifactTable.selectArtifactBy.to[Seq](ref, artifactName, version))

  def countGithubInfo(): Future[Long] =
    run(GithubInfoTable.count.unique)

  def countProjectSettings(): Future[Long] =
    run(ProjectSettingsTable.count.unique)

  override def computeProjectDependencies(): Future[Seq[ProjectDependency]] =
    run(ArtifactDependencyTable.computeProjectDependency.to[Seq])

  override def computeReleaseDependencies(): Future[Seq[ReleaseDependency]] =
    run(ArtifactDependencyTable.computeReleaseDependency.to[Seq])

  override def insertProjectDependencies(projectDependencies: Seq[ProjectDependency]): Future[Int] =
    run(ProjectDependenciesTable.insertOrUpdate.updateMany(projectDependencies))

  override def insertReleaseDependencies(releaseDependency: Seq[ReleaseDependency]): Future[Int] =
    run(ReleaseDependenciesTable.insertIfNotExists.updateMany(releaseDependency))

  override def countInverseProjectDependencies(projectRef: Project.Reference): Future[Int] =
    run(ProjectDependenciesTable.countInverseDependencies.unique(projectRef))

  override def getDirectReleaseDependencies(
      ref: Project.Reference,
      version: SemanticVersion
  ): Future[Seq[ReleaseDependency.Direct]] =
    run(ReleaseDependenciesTable.getDirectDependencies.to[Seq]((ref, version)))

  override def getReverseReleaseDependencies(ref: Project.Reference): Future[Seq[ReleaseDependency.Reverse]] =
    run(ReleaseDependenciesTable.getReverseDependencies.to[Seq](ref))

  override def deleteDependenciesOfMovedProject(): Future[Unit] =
    for {
      moved <- run(ProjectTable.selectProjectByGithubStatus.to[Seq]("Moved"))
      _ <- run(ProjectDependenciesTable.deleteBySource.updateMany(moved))
      _ <- run(ProjectDependenciesTable.deleteByTarget.updateMany(moved))
    } yield ()

  override def computeAllProjectsCreationDates(): Future[Seq[(Instant, Project.Reference)]] =
    run(ArtifactTable.selectOldestByProject.to[Seq])

  override def updateProjectCreationDate(ref: Project.Reference, creationDate: Instant): Future[Unit] =
    run(ProjectTable.updateCreationDate.run((creationDate, ref))).map(_ => ())

  override def getAllGroupIds(): Future[Seq[Artifact.GroupId]] =
    run(ArtifactTable.selectGroupIds.to[Seq])

  override def getAllMavenReferences(): Future[Seq[Artifact.MavenReference]] =
    run(ArtifactTable.selectMavenReference.to[Seq])

  override def insertSession(userId: UUID, userState: UserState): Future[Unit] =
    run(UserSessionsTable.insertOrUpdate.run((userId, userState)).map(_ => ()))

  override def getSession(userId: UUID): Future[Option[UserState]] =
    run(UserSessionsTable.selectUserSessionById.to[Seq](userId)).map(_.headOption)

  override def getAllSessions(): Future[Seq[(UUID, UserState)]] =
    run(UserSessionsTable.selectAllUserSessions.to[Seq])

  override def deleteSession(userId: UUID): Future[Unit] =
    run(UserSessionsTable.deleteByUserId.run(userId).map(_ => ()))

  override def getArtifacts(
      ref: Project.Reference,
      artifactName: Artifact.Name,
      params: ArtifactsPageParams
  ): Future[Seq[Artifact]] =
    run(
      ArtifactTable
        .selectArtifactByParams(params.binaryVersions, params.preReleases)
        .to[Seq](ref, artifactName)
    )

  override def countVersions(ref: Project.Reference): Future[Long] =
    run(ArtifactTable.countVersionsByProjct.unique(ref))

  override def getLastVersion(ref: Project.Reference, artifactNameOpt: Option[Artifact.Name]): Future[SemanticVersion] =
    artifactNameOpt match {
      case Some(artifactName) =>
        for {
          versionOpt <- run(ArtifactTable.selectLastReleaseVersionByArtifactName.option((ref, artifactName)))
          version <- versionOpt match {
            case Some(version) => Future.successful(version)
            case None          => run(ArtifactTable.selectLastVersionByArtifactName.unique((ref, artifactName)))
          }
        } yield version
      case None =>
        for {
          versionOpt <- run(ArtifactTable.selectLastReleaseVersion.option(ref))
          version <- versionOpt match {
            case Some(version) => Future.successful(version)
            case None          => run(ArtifactTable.selectLastVersion.unique(ref))
          }
        } yield version
    }

  def moveProject(
      ref: Project.Reference,
      githubInfo: GithubInfo,
      status: GithubStatus.Moved
  ): Future[Unit] =
    for {
      oldProject <- getProject(ref)
      _ <- updateGithubStatus(ref, status)
      _ <- run(ProjectTable.insertIfNotExists.run((status.destination, GithubStatus.Ok(status.updateDate))))
      _ <- updateProjectSettings(status.destination, oldProject.map(_.settings).getOrElse(Project.Settings.default))
      _ <- run(GithubInfoTable.insertOrUpdate.run(status.destination, githubInfo, githubInfo))
    } yield ()

  def updateGithubStatus(ref: Project.Reference, githubStatus: GithubStatus): Future[Unit] =
    run(ProjectTable.updateGithubStatus.run(githubStatus, ref)).map(_ => ())

  private def run[A](v: doobie.ConnectionIO[A]): Future[A] =
    v.transact(xa).unsafeToFuture()
}
