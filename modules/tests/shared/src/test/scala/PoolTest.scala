// Copyright (c) 2018-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package tests

import ffstest.FTest
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import scala.concurrent.duration._
import skunk.util.Pool
import cats.effect.Ref
import skunk.util.Pool.ResourceLeak
import cats.effect.Deferred
import scala.util.Random
import skunk.util.Pool.ShutdownException
import org.typelevel.otel4s.trace.Tracer
import skunk.util.Recycler

class PoolTest extends FTest {

  case class UserFailure() extends Exception("user failure")
  case class AllocFailure() extends Exception("allocation failure")
  case class FreeFailure() extends Exception("free failure")
  case class ResetFailure() extends Exception("reset failure")

  val ints: IO[Resource[IO, Int]] =
    Ref[IO].of(1).map { ref =>
      val next = ref.modify(n => (n + 1, n.pure[IO])).flatten
      Resource.make(next)(_ => IO.unit)
    }

  // list of computations into computation that yields results one by one
  def yielding[A](fas: IO[A]*): IO[IO[A]] =
    Ref[IO].of(fas.toList).map { ref =>
      ref.modify {
        case Nil       => (Nil, IO.raiseError(new Exception("No more values!")))
        case fa :: fas => (fas, fa)
      } .flatten
    }

  def resourceYielding[A](fas: IO[A]*): IO[Resource[IO, A]] =
    yielding(fas: _*).map(Resource.make(_)(_ => IO.unit))

  // This test leaks
  tracedTest("error in alloc is rethrown to caller (immediate)") {
    val rsrc = Resource.make(IO.raiseError[String](AllocFailure()))(_ => IO.unit)
    val pool = Pool.ofF({(_: Tracer[IO]) => rsrc}, 42)(Recycler.success)
    pool.use(_(Tracer[IO]).use(_ => IO.unit)).assertFailsWith[AllocFailure]
  }

  tracedTest("error in alloc is rethrown to caller (deferral completion following errored cleanup)") {
    resourceYielding(IO(1), IO.raiseError(AllocFailure())).flatMap { r =>
      val p = Pool.ofF({(_: Tracer[IO]) => r}, 1)(Recycler[IO, Int](_ => IO.raiseError(ResetFailure())))
      p.use { r =>
        for {
          d  <- Deferred[IO, Unit]
          f1 <- r(Tracer[IO]).use(n => assertEqual("n should be 1", n, 1) *> d.get).assertFailsWith[ResetFailure].start
          f2 <- r(Tracer[IO]).use(_ => fail[Int]("should never get here")).assertFailsWith[AllocFailure].start
          _  <- d.complete(())
          _  <- f1.join
          _  <- f2.join
        } yield ()
      }
    }
  }

  tracedTest("error in alloc is rethrown to caller (deferral completion following failed cleanup)") {
    resourceYielding(IO(1), IO.raiseError(AllocFailure())).flatMap { r =>
      val p = Pool.ofF({(_: Tracer[IO]) => r}, 1)(Recycler.failure)
      p.use { r =>
        for {
          d  <- Deferred[IO, Unit]
          f1 <- r(ioTracer).use(n => assertEqual("n should be 1", n, 1) *> d.get).start
          f2 <- r(ioTracer).use(_ => fail[Int]("should never get here")).assertFailsWith[AllocFailure].start
          _  <- d.complete(())
          _  <- f1.join
          _  <- f2.join
        } yield ()
      }
    }
  }

  tracedTest("provoke dangling deferral cancellation") {
    ints.flatMap { r =>
      val p = Pool.ofF({(_: Tracer[IO]) => r}, 1)(Recycler.failure)
      Deferred[IO, Either[Throwable, Int]].flatMap { d1 =>
        p.use { r =>
          for {
            d <- Deferred[IO, Unit]
            _ <- r(ioTracer).use(_ => d.complete(()) *> IO.never).start // leaked forever
            _ <- d.get // make sure the resource has been allocated
            f <- r(ioTracer).use(_ => fail[Int]("should never get here")).attempt.flatMap(d1.complete).start // defer
            _ <- IO.sleep(100.milli) // ensure that the fiber has a chance to run
          } yield f
        } .assertFailsWith[ResourceLeak].flatMap {
          case ResourceLeak(1, 0, 1) => d1.get.flatMap(_.liftTo[IO])
          case e                     => e.raiseError[IO, Unit]
        } .assertFailsWith[ShutdownException.type].void
    }
  }}

  tracedTest("error in free is rethrown to caller") {
    val rsrc = Resource.make("foo".pure[IO])(_ => IO.raiseError(FreeFailure()))
    val pool = Pool.ofF({(_: Tracer[IO]) => rsrc}, 42)(Recycler.success)
    pool.use(_(ioTracer).use(_ => IO.unit)).assertFailsWith[FreeFailure]
  }

  tracedTest("error in reset is rethrown to caller") {
    val rsrc = Resource.make("foo".pure[IO])(_ => IO.unit)
    val pool = Pool.ofF({(_: Tracer[IO]) => rsrc}, 42)(Recycler[IO, String](_ => IO.raiseError(ResetFailure())))
    pool.use(_(ioTracer).use(_ => IO.unit)).assertFailsWith[ResetFailure]
  }

  tracedTest("reuse on serial access") {
    ints.map(a => Pool.ofF({(_: Tracer[IO]) => a}, 3)(Recycler.success)).flatMap { factory =>
      factory.use { pool =>
        pool(ioTracer).use { n =>
          assertEqual("first num should be 1", n, 1)
        } *>
        pool(ioTracer).use { n =>
          assertEqual("we should get it again", n, 1)
        }
      }
    }
  }

  tracedTest("allocation on nested access") {
    ints.map(a => Pool.ofF({(_: Tracer[IO]) => a}, 3)(Recycler.success)).flatMap { factory =>
      factory.use { pool =>
        pool(ioTracer).use { n =>
          assertEqual("first num should be 1", n, 1) *>
          pool(ioTracer).use { n =>
            assertEqual("but this one should be 2", n, 2)
          } *>
          pool(ioTracer).use { n =>
            assertEqual("and again", n, 2)
          }
        }
      }
    }
  }

  tracedTest("allocated resource can cause a leak, which will be detected on finalization") {
    ints.map(a => Pool.ofF({(_: Tracer[IO]) => a}, 3)(Recycler.success)).flatMap { factory =>
      factory.use { pool =>
        pool(ioTracer).allocated
      } .assertFailsWith[ResourceLeak].flatMap {
        case ResourceLeak(expected, actual, _) =>
          assert("expected 1 leakage", expected - actual == 1)
      }
    }
  }

  tracedTest("unmoored fiber can cause a leak, which will be detected on finalization") {
    ints.map(a => Pool.ofF({(_: Tracer[IO]) => a}, 3)(Recycler.success)).flatMap { factory =>
      factory.use { pool =>
        pool(ioTracer).use(_ => IO.never).start *>
        IO.sleep(100.milli) // ensure that the fiber has a chance to run
      } .assertFailsWith[ResourceLeak].flatMap {
        case ResourceLeak(expected, actual, _) =>
          assert("expected 1 leakage", expected - actual == 1)
      }
    }
  }

  // Concurrency tests below. These are nondeterministic and need a lot of exercise.

  val PoolSize = 10
  val ConcurrentTasks = 500

  val shortRandomDelay = IO((Random.nextInt() % 100).abs.milliseconds)

  tracedTest("progress and safety with many fibers") {
    ints.map(a => Pool.ofF({(_: Tracer[IO]) => a}, PoolSize)(Recycler.success)).flatMap { factory =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        factory.use { p =>
          p(ioTracer).use { _ =>
            for {
              t <- shortRandomDelay
              _ <- IO.sleep(t)
            } yield ()
          }
        }
      }
    }
  }

  tracedTest("progress and safety with many fibers and cancellation") {
    ints.map(a => Pool.ofF({(_: Tracer[IO]) => a}, PoolSize)(Recycler.success)).flatMap { factory =>
      factory.use { pool =>
        (1 to ConcurrentTasks).toList.parTraverse_{_ =>
          for {
            t <- shortRandomDelay
            f <- pool(ioTracer).use(_ => IO.sleep(t)).start
            _ <- if (t > 50.milliseconds) f.join else f.cancel
          } yield ()
        }
      }
    }
  }

  tracedTest("progress and safety with many fibers and user failures") {
    ints.map(a => Pool.ofF({(_: Tracer[IO]) => a}, PoolSize)(Recycler.success)).flatMap { factory =>
      factory.use { pool =>
        (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
          pool(ioTracer).use { _ =>
            for {
              t <- shortRandomDelay
              _ <- IO.sleep(t)
              _ <- IO.raiseError(UserFailure()).whenA(t < 50.milliseconds)
            } yield ()
          } .attempt // swallow errors so we don't fail fast
        }
      }
    }
  }

  tracedTest("progress and safety with many fibers and allocation failures") {
    val alloc = IO(Random.nextBoolean()).flatMap {
      case true  => IO.unit
      case false => IO.raiseError(AllocFailure())
    }
    val rsrc = Resource.make(alloc)(_ => IO.unit)
    Pool.ofF({(_: Tracer[IO]) => rsrc}, PoolSize)(Recycler.success).use { pool =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        pool(ioTracer).use { _ =>
          IO.unit
        } .attempt
      }
    }
  }

  tracedTest("progress and safety with many fibers and freeing failures") {
    val free = IO(Random.nextBoolean()).flatMap {
      case true  => IO.unit
      case false => IO.raiseError(FreeFailure())
    }
    val rsrc  = Resource.make(IO.unit)(_ => free)
    Pool.ofF({(_: Tracer[IO]) => rsrc}, PoolSize)(Recycler.success).use { pool =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        pool(ioTracer).use { _ =>
          IO.unit
        } .attempt
      }
    } .handleErrorWith {
      // cleanup here may raise an exception, so we need to handle that
      case FreeFailure() => IO.unit
    }
  }

  tracedTest("progress and safety with many fibers and reset failures") {
    val recycle = IO(Random.nextInt(3)).flatMap {
      case 0 => true.pure[IO]
      case 1 => false.pure[IO]
      case 2 => IO.raiseError(ResetFailure())
    }
    val rsrc  = Resource.make(IO.unit)(_ => IO.unit)
    Pool.ofF({(_: Tracer[IO]) => rsrc}, PoolSize)(Recycler(_ => recycle)).use { pool =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        pool(ioTracer).use { _ =>
          IO.unit
        } handleErrorWith {
          case ResetFailure() => IO.unit
        }
      }
    }
  }

}
