package scaladex.core.util

import scala.collection.BuildFrom
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

object ScalaExtensions {
  implicit class IterableOnceFutureExtension[A, CC[X] <: IterableOnce[X], To](val in: CC[Future[A]]) extends AnyVal {
    def sequence(implicit bf: BuildFrom[CC[Future[A]], A, To], executor: ExecutionContext): Future[To] =
      Future.sequence(in)
  }

  implicit class TryExtension[A](val in: Try[A]) extends AnyVal {
    def toFuture: Future[A] = in match {
      case Failure(exception) => Future.failed(exception)
      case Success(value)     => Future.successful(value)
    }
  }

  implicit class OptionExtension[A](val in: Option[A]) extends AnyVal {
    def toFuture: Future[A] = in match {
      case None        => Future.failed(new NoSuchElementException("None.get"))
      case Some(value) => Future.successful(value)
    }
  }

  implicit class FutureExtension[A](val in: Future[A]) extends AnyVal {
    def mapFailure(f: Throwable => Throwable)(implicit ec: ExecutionContext): Future[A] =
      in.recoverWith { case NonFatal(e) => Future.failed(f(e)) }

    def failWithTry(implicit ec: ExecutionContext): Future[Try[A]] =
      in.map(Success(_)).recover { case NonFatal(e) => Failure(e) }
  }

  /**
   * Warning: it is not lazy on lazy collections
   */
  implicit class IterableOnceExtension[A, CC[X] <: IterableOnce[X]](val in: CC[A]) extends AnyVal {
    def mapSync[B](f: A => Future[B])(implicit ec: ExecutionContext, bf: BuildFrom[CC[A], B, CC[B]]): Future[CC[B]] =
      in.iterator
        .foldLeft(Future.successful(bf.newBuilder(in))) { (builderF, a) =>
          for {
            builder <- builderF
            b <- f(a)
          } yield builder.addOne(b)
        }
        .map(_.result())
  }

  implicit class IteratorExtension[A](iterator: Iterator[A]) {
    def foreachSync(f: A => Future[Unit])(implicit ec: ExecutionContext): Future[Unit] =
      if (iterator.hasNext) f(iterator.next()).flatMap(_ => iterator.foreachSync(f))
      else Future.successful(())
  }

  implicit class FiniteDurationExtension(duration: FiniteDuration) {
    def prettyPrint: String =
      duration match {
        case duration if duration.toSeconds == 0 => s"${duration.toMillis} milliseconds"
        case duration if duration.toMinutes == 0 => s"${duration.toSeconds} seconds"
        case duration if duration.toHours == 0   => s"${duration.toMinutes} minutes"
        case duration if duration.toDays == 0    => s"${duration.toHours} hours"
        case duration if duration.toDays != 0    => s"${duration.toDays} days"
        case _                                   => duration.toString()
      }
  }
}
