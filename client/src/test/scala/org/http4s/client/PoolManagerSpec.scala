/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package client

import cats.effect._
import fs2.Stream
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class PoolManagerSpec(name: String) extends Http4sSpec {
  locally {
    val _ = name
  }
  val key = RequestKey(Uri.Scheme.http, Uri.Authority(host = ipv4"127.0.0.1"))
  val otherKey = RequestKey(Uri.Scheme.http, Uri.Authority(host = Uri.RegName("localhost")))

  class TestConnection extends Connection[IO] {
    def isClosed = false
    def isRecyclable = true
    def requestKey = key
    def shutdown() = ()
  }

  def mkPool(
      maxTotal: Int = 1,
      maxWaitQueueLimit: Int = 2,
      requestTimeout: Duration = Duration.Inf
  ) =
    ConnectionManager.pool(
      builder = _ => IO(new TestConnection()),
      maxTotal = maxTotal,
      maxWaitQueueLimit = maxWaitQueueLimit,
      maxConnectionsPerRequestKey = _ => 5,
      responseHeaderTimeout = Duration.Inf,
      requestTimeout = requestTimeout,
      executionContext = ExecutionContext.Implicits.global
    )

  "A pool manager" should {
    "wait up to maxWaitQueueLimit" in {
      (for {
        pool <- mkPool(maxTotal = 1, maxWaitQueueLimit = 2)
        _ <- pool.borrow(key)
        att <-
          Stream(Stream.eval(pool.borrow(key))).repeat
            .take(2)
            .covary[IO]
            .parJoinUnbounded
            .compile
            .toList
            .attempt
      } yield att).unsafeRunTimed(2.seconds) must_== None
    }

    "throw at maxWaitQueueLimit" in {
      (for {
        pool <- mkPool(maxTotal = 1, maxWaitQueueLimit = 2)
        _ <- pool.borrow(key)
        att <-
          Stream(Stream.eval(pool.borrow(key))).repeat
            .take(3)
            .covary[IO]
            .parJoinUnbounded
            .compile
            .toList
            .attempt
      } yield att).unsafeRunTimed(2.seconds) must_== Some(Left(WaitQueueFullFailure()))
    }

    "wake up a waiting connection on release" in {
      (for {
        pool <- mkPool(maxTotal = 1, maxWaitQueueLimit = 1)
        conn <- pool.borrow(key)
        fiber <- pool.borrow(key).start // Should be one waiting
        _ <- pool.release(conn.connection)
        _ <- fiber.join
      } yield ()).unsafeRunTimed(2.seconds) must_== Some(())
    }

    // this is a regression test for https://github.com/http4s/http4s/issues/2962
    "fail expired connections and then wake up a non-expired waiting connection on release" in skipOnCi {
      val timeout = 50.milliseconds
      (for {
        pool <- mkPool(maxTotal = 1, maxWaitQueueLimit = 3, requestTimeout = timeout)
        conn <- pool.borrow(key)
        waiting1 <- pool.borrow(key).start
        waiting2 <- pool.borrow(key).start
        _ <- IO.sleep(timeout + 20.milliseconds)
        waiting3 <- pool.borrow(key).start
        _ <- pool.release(conn.connection)
        result1 <- waiting1.join
        result2 <- waiting2.join
        result3 <- waiting3.join
      } yield (result1, result2, result3)).unsafeRunTimed(10.seconds) must beSome.like {
        case (result1, result2, result3) =>
          result1 must_== Outcome.errored[IO, Throwable, Any](WaitQueueTimeoutException)
          result2 must_== Outcome.errored[IO, Throwable, Any](WaitQueueTimeoutException)
          result3 must beLike { case Outcome.Succeeded(_) => ok }
      }
    }

    "wake up a waiting connection on invalidate" in {
      (for {
        pool <- mkPool(maxTotal = 1, maxWaitQueueLimit = 1)
        conn <- pool.borrow(key)
        fiber <- pool.borrow(key).start // Should be one waiting
        _ <- pool.invalidate(conn.connection)
        _ <- fiber.join
      } yield ()).unsafeRunTimed(2.seconds) must_== Some(())
    }

    "close an idle connection when at max total connections" in {
      (for {
        pool <- mkPool(maxTotal = 1, maxWaitQueueLimit = 1)
        conn <- pool.borrow(key)
        _ <- pool.release(conn.connection)
        fiber <- pool.borrow(otherKey).start
        _ <- fiber.join
      } yield ()).unsafeRunTimed(2.seconds) must_== Some(())
    }

    "wake up a waiting connection for a different request key on release" in {
      (for {
        pool <- mkPool(maxTotal = 1, maxWaitQueueLimit = 1)
        conn <- pool.borrow(key)
        fiber <- pool.borrow(otherKey).start
        _ <- pool.release(conn.connection)
        _ <- fiber.join
      } yield ()).unsafeRunTimed(2.seconds) must_== Some(())
    }
  }

  "A WaitQueueFullFailure" should {
    "render message properly" in {
      (new WaitQueueFullFailure).toString() must contain("Wait queue is full")
    }
  }
}
