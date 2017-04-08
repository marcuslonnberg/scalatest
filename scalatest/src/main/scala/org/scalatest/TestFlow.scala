/*
 * Copyright 2001-2017 Artima, Inc.
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
package org.scalatest

import org.scalatest.events.{MotionToSuppress, TestFailed, TestStarting, TestSucceeded, TestCanceled, TestPending}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import org.scalatest.exceptions.{DuplicateTestNameException, PayloadField, TestCanceledException, TestPendingException}
import org.scalactic.source

trait Test0[A] { thisTest0 =>
  def apply(): A // This is the test function, like what we pass into withFixture
  def name: String
  def testNames: Set[String]
  def andThen[B](next: Test1[A, B])(implicit pos: source.Position): Test0[B] = {
    thisTest0.testNames.find(tn => next.testNames.contains(tn)) match {
      case Some(testName) => throw new DuplicateTestNameException(testName, pos)
      case _ =>
    }
    new Test0[B] {
      def apply(): B = next(thisTest0())
      val name = thisTest0.name
      def testNames: Set[String] = thisTest0.testNames ++ next.testNames // TODO: Ensure iterator order is reasonable, either depth or breadth first
      override def runTests(suite: Suite, testName: Option[String], args: Args): (Option[B], Status) = {
        val (res0, status) = thisTest0.runTests(suite, testName, args)
        res0 match {
          case Some(res0) => next.runTests(suite, testName, args, res0)
          case None =>
            next.cancel(suite, args)
            (None, status)
        }
      }
    }
  }
  def runTests(suite: Suite, testName: Option[String], args: Args): (Option[A], Status) = {
    args.reporter(TestStarting(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", Some(MotionToSuppress),
      None, None))
    try {
      val result = thisTest0()
      args.reporter(TestSucceeded(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, None, None,
        None, None))
      (Some(result), SucceededStatus)
    }
    catch {
      case tce: TestCanceledException =>
        val message = Suite.getMessageForException(tce)
        val payload =
          tce match {
            case optPayload: PayloadField =>
              optPayload.payload
            case _ =>
              None
          }
        //val formatter = getEscapedIndentedTextForTest(testText, level, includeIcon)
        args.reporter(TestCanceled(args.tracker.nextOrdinal(), message, suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, Some(tce), None, Some(MotionToSuppress), None, None, payload))
        (None, SucceededStatus)

      case tpe: TestPendingException =>
        args.reporter(TestPending(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, None, Some(MotionToSuppress), None))
        (None, SucceededStatus)

      case t: Throwable =>
        val message = Suite.getMessageForException(t)
        val payload =
          t match {
            case optPayload: PayloadField =>
              optPayload.payload
            case _ =>
              None
          }
        args.reporter(TestFailed(args.tracker.nextOrdinal(), message, suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, Some(t), None, Some(MotionToSuppress), None, None, payload))
        (None, FailedStatus)
    }
  }
}

object Test0 {
  def apply[A](testName: String)(f: => A): Test0[A] =
    new Test0[A] {
      def apply(): A = f
      val name: String = testName
      def testNames: Set[String] = Set(testName)
    }
}

trait Test1[A, B] { thisTest1 =>
  def apply(a: A): B // This is the test function, like what we pass into withFixture
  def name: String
  def cancel(suite: Suite, args: Args): Unit = {
    args.reporter(TestStarting(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", Some(MotionToSuppress),
      None, None))
    args.reporter(TestCanceled(args.tracker.nextOrdinal(), "Dependent test did not pass.", suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, None, None, None, None, None, None))
  }
  def andThen[C](next: Test1[B, C])(implicit pos: source.Position): Test1[A, C] = {
    thisTest1.testNames.find(tn => next.testNames.contains(tn)) match {
      case Some(testName) => throw new DuplicateTestNameException(testName, pos)
      case _ =>
    }

    new Test1[A, C] {
      def apply(a: A): C = next(thisTest1(a))

      val name = thisTest1.name

      def testNames: Set[String] = thisTest1.testNames ++ next.testNames // TODO: Ensure iterator order is reasonable, either depth or breadth first
      override def cancel(suite: Suite, args: Args): Unit = {
        args.reporter(TestStarting(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", Some(MotionToSuppress),
          None, None))
        args.reporter(TestCanceled(args.tracker.nextOrdinal(), "Dependent test did not pass.", suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, None, None, None, None, None, None))
        next.cancel(suite, args)
      }
      override def runTests(suite: Suite, testName: Option[String], args: Args, input: A): (Option[C], Status) = {
        val (res0, status) = thisTest1.runTests(suite, testName, args, input)
        res0 match {
          case Some(res0) => next.runTests(suite, testName, args, res0)
          case None =>
            next.cancel(suite, args)
            (None, status)
        }

      }
    }
  }
  def compose[C](prev: Test1[C, A])(implicit pos: source.Position): Test1[C, B] = {
    thisTest1.testNames.find(tn => prev.testNames.contains(tn)) match {
      case Some(testName) => throw new DuplicateTestNameException(testName, pos)
      case _ =>
    }

    new Test1[C, B] {
      def apply(c: C): B = thisTest1(prev(c))

      val name = prev.name

      def testNames: Set[String] = prev.testNames ++ thisTest1.testNames

      override def runTests(suite: Suite, testName: Option[String], args: Args, input: C): (Option[B], Status) = {
        val (res0, status) = prev.runTests(suite, testName, args, input)
        res0 match {
          case Some(res0) =>
            thisTest1.runTests(suite, testName, args, res0)

          case None =>
            args.reporter(TestStarting(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), thisTest1.name, "", Some(MotionToSuppress), None, None))
            args.reporter(TestCanceled(args.tracker.nextOrdinal(), "Dependent test did not pass.", suite.suiteName, suite.suiteId, Some(suite.getClass.getName), thisTest1.name, "", collection.immutable.IndexedSeq.empty, None, None, None, None, None, None))
            (None, SucceededStatus)
        }
      }
    }
  }
  def compose(prev: Test0[A])(implicit pos: source.Position): Test0[B] = {
    thisTest1.testNames.find(tn => prev.testNames.contains(tn)) match {
      case Some(testName) => throw new DuplicateTestNameException(testName, pos)
      case _ =>
    }

    new Test0[B] {
      def apply(): B = thisTest1(prev())

      val name = prev.name

      def testNames: Set[String] = prev.testNames ++ thisTest1.testNames

      override def runTests(suite: Suite, testName: Option[String], args: Args): (Option[B], Status) = {
        val (res0, status) = prev.runTests(suite, testName, args)
        res0 match {
          case Some(res0) =>
            thisTest1.runTests(suite, testName, args, res0)

          case None =>
            args.reporter(TestStarting(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), thisTest1.name, "", Some(MotionToSuppress), None, None))
            args.reporter(TestCanceled(args.tracker.nextOrdinal(), "Dependent test did not pass.", suite.suiteName, suite.suiteId, Some(suite.getClass.getName), thisTest1.name, "", collection.immutable.IndexedSeq.empty, None, None, None, None, None, None))
            (None, SucceededStatus)
        }
      }
    }
  }
  def testNames: Set[String]
  def runTests(suite: Suite, testName: Option[String], args: Args, input: A): (Option[B], Status) = {
    args.reporter(TestStarting(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", Some(MotionToSuppress), None, None))
    try {
      val result = thisTest1(input)
      args.reporter(TestSucceeded(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, None, None, None, None))
      (Some(result), SucceededStatus)
    }
    catch {
      case tce: TestCanceledException =>
        val message = Suite.getMessageForException(tce)
        val payload =
          tce match {
            case optPayload: PayloadField =>
              optPayload.payload
            case _ =>
              None
          }
        //val formatter = getEscapedIndentedTextForTest(testText, level, includeIcon)
        args.reporter(TestCanceled(args.tracker.nextOrdinal(), message, suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, Some(tce), None, Some(MotionToSuppress), None, None, payload))
        (None, SucceededStatus)

      case tce: TestPendingException =>
        args.reporter(TestPending(args.tracker.nextOrdinal(), suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, None, Some(MotionToSuppress), None))
        (None, SucceededStatus)

      case t: Throwable =>
        val message = Suite.getMessageForException(t)
        val payload =
          t match {
            case optPayload: PayloadField =>
              optPayload.payload
            case _ =>
              None
          }
        args.reporter(TestFailed(args.tracker.nextOrdinal(), message, suite.suiteName, suite.suiteId, Some(suite.getClass.getName), name, "", collection.immutable.IndexedSeq.empty, Some(t), None, Some(MotionToSuppress), None, None, payload))
        (None, FailedStatus)
    }
  }
}

object Test1 {
  def apply[A, B](testName: String)(f: A => B): Test1[A, B] =
    new Test1[A, B] {
      def apply(a: A): B = f(a)
      val name: String = testName
      def testNames: Set[String] = Set(testName) 
    }
}

trait TestFlow[A] extends Suite {

  def flow: Test0[A]

  override def runTests(testName: Option[String], args: Args): Status = {
    val (res, status) = flow.runTests(this, testName, args)
    status
  }

}

/*
// Ability to join and split
trait TestSplitter {
  // holds onto a collection of TestFlows all of which have the same input type, but could different
  // output types.
}

trait TestJoiner {

}
*/
