package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties

import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import TestInstances.equalProcessTask

//import scalaz.syntax.equal._
//import scalaz.std.anyVal._
//import TestInstances.equalProcessTask

/**
 * We test various termination causes here,
 * including the correct propagation of causes
 * from merging combinator (pipe, tee, wye, njoin) at various scenarios
 */
object CauseSpec extends Properties("cause") {

  property("suspend") = secure {
    val source = Process(1, 2, 3).toSource
    val halted: Process[Task, Int] = halt
    val failed: Process[Task, Int] = fail(Bwahahaa)

    ("result" |: source.runLog.run == Vector(1,2,3))
    .&& ("source" |: suspend(source) === source)
    .&& ("halt" |: suspend(halted) === halted)
    .&& ("failed" |: suspend(failed) === failed)
    .&& ("take(1)" |: suspend(source.take(1)) === source.take(1))
    .&& ("repeat.take(10)" |: suspend(source.repeat.take(10)) === source.repeat.take(10))
    .&& ("eval" |: suspend(eval(Task.now(1))) === eval(Task.delay(1)))
    .&& ("kill" |: suspend(source).kill.onHalt({ case Kill => emit(99); case other => emit(0)}) === emit(99).toSource)
  }


  property("pipe.terminated.p1") = secure {
    val source = Process(1) ++ Process(2) ++ Process(3).toSource
    var upReason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
    source.onHalt{cause => upReason = Some(cause); Halt(cause)}
    .pipe(process1.take(2))
    .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
    .runLog.run

    (result == Vector(1,2))
    .&&(upReason == Some(Kill))
    .&&(downReason == Some(End))
  }

  property("pipe.terminated.p1.with.upstream") = secure {
    val source = emitAll(Seq(1,2,3)).toSource
    var upReason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source.onHalt{cause => upReason = Some(cause); Halt(cause)}
      .pipe(process1.take(2))
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run


    (result == Vector(1,2))
    .&&(upReason == Some(Kill)) //onHalt which remains in the pipe's source gets Kill
    .&&(downReason == Some(End))
  }

  property("pipe.terminated.upstream") = secure {
    val source = emitAll(Seq(1,2,3)).toSource
    var id1Reason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source
      .pipe(process1.id[Int].onHalt{cause => id1Reason = Some(cause) ; Halt(cause)})
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run

    (result == Vector(1,2,3))
    .&&(id1Reason == Some(Kill))
    .&&(downReason == Some(End))
  }

  property("pipe.killed.upstream") = secure {
    val source = (Process(1) ++ Process(2).kill).toSource
    var id1Reason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source
      .pipe(process1.id[Int].onHalt{cause => id1Reason = Some(cause) ; Halt(cause)})
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run

    (result == Vector(1))
    .&&(id1Reason == Some(Kill))
    .&&(downReason == Some(Kill))
  }


  property("tee.terminated.tee") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(halt)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector())
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("tee.terminated.tee.onLeft") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitL.repeat)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector(0,1,2))
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("tee.terminated.tee.onRight") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitR.repeat)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,1,2))
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(End))
    .&& (processReason == Some(End))
  }

  property("tee.terminated.left.onLeft") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = halt onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitL.repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector())
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
  }


  property("tee.terminated.right.onRight") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = halt onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitR.repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector())
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(End))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
  }

  property("tee.terminated.leftAndRight.onLeftAndRight") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10,1,11,2,12))
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill)) //onHalt which remains on the right side gets Kill
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill)) //killed due left input exhausted awaiting left branch
  }

  property("tee.terminated.leftAndRight.onRightAndLeft") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitR[Int] ++ awaitL[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(10,0,11,1,12,2))
    .&& (leftReason == Some(Kill)) //was killed due tee awaited right and that was `End`.
    .&& (rightReason == Some(End))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill)) //killed due right input exhausted awaiting right branch
  }

  property("tee.terminated.downstream") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .take(2)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10))
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill)) //Tee is killed because downstream terminated
  }

  property("tee.kill.left") = secure {
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    var beforePipeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.take(1) ++ Halt(Kill)
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => beforePipeReason = Some(c); Halt(c)}
      .take(2)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector(0,10))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
    .&& (beforePipeReason == Some(Kill))
  }

  property("tee.kill.right") = secure {
    var leftReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    var beforePipeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10).take(1) ++ Halt(Kill)


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => beforePipeReason = Some(c); Halt(c)}
      .take(2)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10))
    .&& (leftReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
    .&& (beforePipeReason == Some(Kill))
  }


  property("tee.pipe.kill") = secure {
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    var pipeReason: Option[Cause] = None
    var beforePipeReason : Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.take(1) ++ Halt(Kill)
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    //note last onHalt before and after pipe
    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => beforePipeReason = Some(c); Halt(c)}
      .pipe(process1.id[Int] onHalt {c => pipeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(Kill))
    .&& (teeReason == Some(Kill))
    .&& (beforePipeReason == Some(Kill))
    .&& (pipeReason == Some(Kill))
  }


  property("wye.terminated.wye") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(halt)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector())
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("wye.terminated.wye.onLeft") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10).repeat onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(awaitL.repeat.asInstanceOf[Wye[Int,Int,Int]].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector(0,1,2))
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(End))
  }

  property("wye.terminated.wye.onRight") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.repeat onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(awaitR.repeat.asInstanceOf[Wye[Int,Int,Int]].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    println(wyeReason)
    (process == Vector(10,11,12))
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(End))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("wye.kill.merge.onLeft") = secure {
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src ++ Halt(Kill)
    val right = src.map(_ + 10).repeat onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(wye.merge[Int].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process.filter(_ < 10) == Vector(0,1,2))
    .&& (rightReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(Kill))
  }


  property("wye.kill.merge.onRight") = secure {
    var leftReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.repeat onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) ++ Halt(Kill)


    val process =
      left.wye(right)(wye.merge[Int].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process.filter(_ >= 10) == Vector(10,11,12))
    .&& (leftReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(Kill))
  }

  property("wye.terminated.wye.onRight") = secure {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var pipeReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.repeat onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.repeat onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(wye.merge[Int].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => pipeReason = Some(c); Halt(c)}
      .pipe(take(10))
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run
 
    (process.size == 10)
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (pipeReason == Some(Kill))
    .&& (processReason == Some(End))
  }


}
