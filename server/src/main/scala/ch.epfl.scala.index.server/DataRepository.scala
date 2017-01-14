package ch.epfl.scala.index
package server

import model._
import model.misc._
import data.project.ProjectForm
import release._
import misc.Pagination
import data.elastic._
import com.sksamuel.elastic4s._
import ElasticDsl._
import org.elasticsearch.common.lucene.search.function.FieldValueFactorFunction.Modifier
import org.elasticsearch.search.sort.SortOrder

import scala.concurrent.{ExecutionContext, Future}
import scala.language.reflectiveCalls

class DataRepository(github: Github)(private implicit val ec: ExecutionContext) {
  private def hideId(p: Project) = p.copy(id = None)

  val resultsPerPage = 20

  val sortQuery = (sorting: Option[String]) =>
    sorting match {
      case Some("stars") =>
        fieldSort("github.stars") missing "0" order SortOrder.DESC mode MultiMode.Avg
      case Some("forks") =>
        fieldSort("github.forks") missing "0" order SortOrder.DESC mode MultiMode.Avg
      case Some("relevant") => scoreSort
      case Some("created") => fieldSort("created") order SortOrder.DESC
      case Some("updated") => fieldSort("updated") order SortOrder.DESC
      case _ => scoreSort
  }

  private def query(q: QueryDefinition,
                    page: PageIndex,
                    total: Int,
                    sorting: Option[String]): Future[(Pagination, List[Project])] = {

    val clampedPage = if (page <= 0) 1 else page

    esClient.execute {
      search
        .in(indexName / projectsCollection)
        .query(q)
        .start(total * (clampedPage - 1))
        .limit(total)
        .sort(sortQuery(sorting))
    }.map(
      r =>
        (
          Pagination(
            current = clampedPage,
            totalPages = Math.ceil(r.totalHits / total.toDouble).toInt,
            total = r.totalHits
          ),
          r.as[Project].toList.map(hideId)
      ))
  }

  private def getQuery(queryString: String,
                       userRepos: Set[GithubRepo] = Set(),
                       targetFiltering: Option[ScalaTarget] = None,
                       cli: Boolean = false) = {
    def replaceField(queryString: String, input: String, replacement: String) = {
      val regex = s"(\\s|^)$input:".r
      regex.replaceAllIn(queryString, s"$$1$replacement:")
    }

    val translated = replaceField(queryString, "depends-on", "dependencies")

    val escaped =
      if (translated.isEmpty) "*"
      else translated.replaceAllLiterally("/", "\\/")

    val mustQueryTarget =
      targetFiltering match {
        case Some(t) => List(bool(should(termQuery("targets", t.supportName))))
        case None => Nil
      }

    val cliQuery =
      if (cli) List(termQuery("hasCli", true))
      else Nil

    val reposQueries = if (!userRepos.isEmpty) {
      userRepos.toList.map {
        case GithubRepo(organization, repository) =>
          bool(
            must(
              termQuery("organization", organization.toLowerCase),
              termQuery("repository", repository.toLowerCase)
            )
          )
      }
    } else List()

    val mustQueriesRepos =
      if (userRepos.isEmpty) Nil
      else List(bool(should(reposQueries: _*)))

    val stringQ =
      if (escaped.contains(":")) stringQuery(escaped)
      else stringQuery(escaped + "~").fuzzyPrefixLength(3).defaultOperator("AND")

    functionScoreQuery(
      bool(
        mustQueries = mustQueriesRepos ++ mustQueryTarget ++ cliQuery,
        shouldQueries = List(
          fuzzyQuery("keywords", escaped).boost(2),
          fuzzyQuery("github.description", escaped).boost(1.7),
          fuzzyQuery("repository", escaped),
          fuzzyQuery("organization", escaped),
          fuzzyQuery("artifacts", escaped),
          stringQ.boost(0.01) // low boost because this query is applied to all the fields of the project (including the github readme)
        ),
        notQueries = List(termQuery("deprecated", true))
      )
    ).scorers(
      // Add a small boost for project that seem to be “popular” (highly depended on or highly starred)
      fieldFactorScore("dependentCount")
        .modifier(Modifier.LOG1P)
        .weight(0.3),
      fieldFactorScore("github.stars")
        .missing(0)
        .modifier(Modifier.LOG1P)
        .weight(0.1)
    ).boostMode("sum")
  }

  def total(queryString: String): Future[Long] = {
    esClient.execute {
      search.in(indexName / projectsCollection).query(getQuery(queryString)).size(0)
    }.map(_.totalHits)
  }

  def find(queryString: String,
           page: PageIndex = 0,
           sorting: Option[String] = None,
           userRepos: Set[GithubRepo] = Set(),
           total: Int = resultsPerPage,
           targetFiltering: Option[ScalaTarget] = None,
           cli: Boolean = false): Future[(Pagination, List[Project])] = {

    query(
      getQuery(queryString, userRepos, targetFiltering, cli),
      page,
      total,
      sorting
    )
  }

  private def uniquelyNamedReleases(releases: List[Release]) =
    releases.groupBy(r => r.reference.artifact).map { case (_, release) => release.head }.toList

  def findWithReleases(queryString: String,
                       page: PageIndex = 0,
                       sorting: Option[String] = None,
                       userRepos: Set[GithubRepo] = Set(),
                       total: Int = resultsPerPage,
                       targetFiltering: Option[ScalaTarget] = None,
                       cli: Boolean = false): Future[(Pagination, List[(Project, List[Release])])] = {
    val queryResults = find(queryString, page, sorting, userRepos, total, targetFiltering, cli)

    queryResults.flatMap {
      case (pagination, projects) => {
        val projectsAndReleases = projects.map(
          p => projectAndReleases(p.reference, ReleaseSelection(None, None, None)))

        Future.sequence(projectsAndReleases).map(s => (pagination, s.collect {
          case Some((project, releases)) => (project, uniquelyNamedReleases(releases))
        }))
      }
    }
  }

  def releases(project: Project.Reference, artifact: Option[String]): Future[List[Release]] = {
    esClient.execute {
      search
        .in(indexName / releasesCollection)
        .query(
          nestedQuery("reference").query(
            bool(
              must(
                artifact.map(termQuery("reference.artifact", _)) ++ List(
                  termQuery("reference.organization", project.organization),
                  termQuery("reference.repository", project.repository))
              )
            )
          )
        )
        .size(500)
    }.map(_.as[Release].toList)
  }

  /**
    * search for a maven artifact
    * @param maven
    * @return
    */
  def maven(maven: MavenReference): Future[Option[Release]] = {

    esClient.execute {
      search
        .in(indexName / releasesCollection)
        .query(
          nestedQuery("maven").query(
            bool(
              must(
                termQuery("maven.groupId", maven.groupId),
                termQuery("maven.artifactId", maven.artifactId),
                termQuery("maven.version", maven.version)
              )
            )
          )
        )
        .limit(1)
    }.map(r => r.as[Release].headOption)
  }

  def project(project: Project.Reference): Future[Option[Project]] = {
    esClient.execute {
      search
        .in(indexName / projectsCollection)
        .query(
          bool(
            must(
              termQuery("organization", project.organization),
              termQuery("repository", project.repository)
            )
          )
        )
        .limit(1)
    }.map(_.as[Project].headOption)
  }

  def projectAndReleases(projectRef: Project.Reference,
                         selection: ReleaseSelection): Future[Option[(Project, List[Release])]] = {
    for {
      project <- project(projectRef)
      releases <- releases(projectRef, selection.artifact)
    } yield project.map((_, releases))
  }

  def projectPage(projectRef: Project.Reference,
                  selection: ReleaseSelection): Future[Option[(Project, ReleaseOptions)]] = {
    val projectAndReleases = this.projectAndReleases(projectRef, selection)

    projectAndReleases.collect {
      case Some((project, releases)) =>
        DefaultRelease(project.repository,
                       selection,
                       releases.toSet,
                       project.defaultStableVersion)
          .map(sel =>
            (project, sel.copy(artifacts = project.artifacts)))
    }
  }

  def updateProject(projectRef: Project.Reference, form: ProjectForm): Future[Boolean] = {
    for {
      updatedProject <- project(projectRef).map(_.map(p => form.update(p)))
      ret <- updatedProject
        .flatMap(
          project =>
            project.id.map(
              id =>
                esClient
                  .execute(update(id) in (indexName / projectsCollection) doc project)
                  .map(_ => true)))
        .getOrElse(Future.successful(false))
    } yield ret
  }

  def latestProjects() = latest[Project](projectsCollection, "created", 12).map(_.map(hideId))
  def latestReleases() = latest[Release](releasesCollection, "released", 12)

  def artifactPage(projectRef: Project.Reference,
                   selection: ReleaseSelection): Future[Option[(Project, List[Release], Release)]] = {
    val projectAndReleases = this.projectAndReleases(projectRef, selection)

    projectAndReleases.collect {
      case Some((project, releases)) =>
        DefaultRelease(project.repository,
          selection,
          releases.toSet,
          project.defaultStableVersion)
          .map(sel =>
            (project, releases, sel.release))
    }
  }

  private def latest[T: HitAs: Manifest](collection: String, by: String, n: Int): Future[List[T]] = {
    esClient.execute {
      search
        .in(indexName / collection)
        .query(
          bool(
            mustQueries = Nil,
            shouldQueries = Nil,
            notQueries = List(termQuery("deprecated", true))
          )
        )
        .sort(fieldSort(by).order(SortOrder.DESC))
        .limit(n)
    }.map(r => r.as[T].toList)
  }

  def keywords() = aggregations("keywords")
  def targets() = aggregations("targets")
  def dependents() = {
    // we remove testing or logging because they are always a dependency
    // we could have another view to compare testing frameworks
    val testOrLogging = Set(
      "akka/akka-slf4j",
      "akka/akka-testkit",
      "etorreborre/specs2",
      "etorreborre/specs2-core",
      "etorreborre/specs2-junit",
      "etorreborre/specs2-mock",
      "etorreborre/specs2-scalacheck",
      "lihaoyi/utest",
      "paulbutcher/scalamock-scalatest-support",
      "playframework/play-specs2",
      "playframework/play-test",
      "rickynils/scalacheck",
      "scala/scala-library",
      "scalatest/scalatest",
      "scalaz/scalaz-scalacheck-binding",
      "scopt/scopt",
      "scoverage/scalac-scoverage-plugin",
      "scoverage/scalac-scoverage-runtime",
      "spray/spray-testkit",
      "typesafehub/scala-logging",
      "typesafehub/scala-logging-slf4j"
    )

    // We find dependent libraries by aggregating the dependency information
    aggregations("dependencies").map(agg =>
      agg.toList.sortBy(_._2)(Descending).filter {
        case (ref, _) =>
          !testOrLogging.contains(ref)
    })
  }

  /**
    * list all tags including number of facets
    * @param field the field name
    * @return
    */
  private def aggregations(field: String): Future[Map[String, Long]] = {

    import scala.collection.JavaConverters._
    import org.elasticsearch.search.aggregations.bucket.terms.StringTerms

    val aggregationName = s"${field}_count"

    esClient.execute {
      search
        .in(indexName / projectsCollection)
        .aggregations(
          aggregation.terms(aggregationName).field(field).size(50)
        )
    }.map(resp => {
      val agg = resp.aggregations.get[StringTerms](aggregationName)
      agg.getBuckets.asScala.toList.collect {
        case b: StringTerms.Bucket => b.getKeyAsString -> b.getDocCount
      }.toMap

    })
  }

  private def maxOption[T: Ordering](xs: List[T]): Option[T] =
    if (xs.isEmpty) None else Some(xs.max)
}
