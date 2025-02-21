package is.hail.expr.ir.functions

import is.hail.asm4s._
import is.hail.expr.Nat
import is.hail.expr.ir.{EmitCodeBuilder, IEmitCode}
import is.hail.types.physical.stypes._
import is.hail.types.physical.stypes.concrete.{SIndexablePointer, SNDArrayPointer, SRNGStateStaticSizeValue, SRNGStateValue}
import is.hail.types.physical.stypes.interfaces._
import is.hail.types.physical.stypes.primitives._
import is.hail.types.physical.{PBoolean, PCanonicalArray, PCanonicalNDArray, PFloat64, PInt32, PType}
import is.hail.types.virtual._
import is.hail.utils.FastIndexedSeq
import net.sourceforge.jdistlib.rng.MersenneTwister
import net.sourceforge.jdistlib.{Beta, Gamma, HyperGeometric, Poisson}

class IRRandomness(seed: Long) {

  // org.apache.commons has no way to statically sample from distributions without creating objects :(
  private[this] val random = new MersenneTwister()
  private[this] val poisState = Poisson.create_random_state()

  // FIXME: these are just combined with some large primes, so probably should be fixed up
  private[this] def hash(pidx: Int): Long =
    seed ^ java.lang.Math.floorMod(pidx * 11399L, 2147483647L)

  def reset(partitionIdx: Int) {
    val combinedSeed = hash(partitionIdx)
    random.setSeed(combinedSeed)
  }

  def runif(min: Double, max: Double): Double = min + (max - min) * random.nextDouble()

  def rint32(n: Int): Int = random.nextInt(n)

  def rint64(): Long = random.nextLong()

  def rint64(n: Long): Long = random.nextLong(n)

  def rcoin(p: Double): Boolean = random.nextDouble() < p

  def rpois(lambda: Double): Double = Poisson.random(lambda, random, poisState)

  def rnorm(mean: Double, sd: Double): Double = mean + sd * random.nextGaussian()

  def rbeta(a: Double, b: Double): Double = Beta.random(a, b, random)

  def rgamma(shape: Double, scale: Double): Double = Gamma.random(shape, scale, random)

  def rhyper(numSuccessStates: Double, numFailureStates: Double, numToDraw: Double): Double =
    HyperGeometric.random(numSuccessStates, numFailureStates, numToDraw, random)

  def rcat(prob: Array[Double]): Int = {
    var i = 0
    var sum = 0.0
    while (i < prob.length) {
      sum += prob(i)
      i += 1
    }
    var draw = random.nextDouble() * sum
    i = 0
    while (draw > prob(i)) {
      draw -= prob(i)
      i += 1
    }
    i
  }
}

object RandomSeededFunctions extends RegistryFunctions {

  // Equivalent to generating an infinite-precision real number in [0, 1),
  // represented as an infinitely long bitstream, and rounding down to the
  // nearest representable floating point number.
  // In contrast, the standard Java and jdistlib generators sample uniformly
  // from a sequence of equidistant floating point numbers in [0, 1), using
  // (nextLong() >>> 11).toDouble / (1L << 53)
  def rand_unif(cb: EmitCodeBuilder, rand_longs: IndexedSeq[Value[Long]]): Code[Double] = {
    assert(rand_longs.size == 4)
    val bits: Settable[Long] = cb.newLocal[Long]("rand_unif_bits", rand_longs(3))
    val exponent: Settable[Int] = cb.newLocal[Int]("rand_unif_exponent", 1022)
    cb.ifx(bits.ceq(0), {
      cb.assign(exponent, exponent - 64)
      cb.assign(bits, rand_longs(2))
      cb.ifx(bits.ceq(0), {
        cb.assign(exponent, exponent - 64)
        cb.assign(bits, rand_longs(1))
        cb.ifx(bits.ceq(0), {
          cb.assign(exponent, exponent - 64)
          cb.assign(bits, rand_longs(0))
        })
      })
    })
    cb.assign(exponent, exponent - bits.numberOfTrailingZeros)
    val result = (exponent.toL << 52) | (rand_longs(0) >>> 12)
    Code.invokeStatic1[java.lang.Double, Long, Double]("longBitsToDouble", result)
  }

  def registerAll() {
    registerSCode3("rand_unif", TRNGState, TFloat64, TFloat64, TFloat64, {
      case (_: Type, _: SType, _: SType, _: SType) => SFloat64
    }) { case (_, cb, rt, rngState: SRNGStateValue, min: SFloat64Value, max: SFloat64Value, errorID) =>
      primitive(cb.memoize(rand_unif(cb, rngState.rand(cb)) * (max.value - min.value) + min.value))
    }

    registerSCode5("rand_unif_nd", TRNGState, TInt64, TInt64, TFloat64, TFloat64, TNDArray(TFloat64, Nat(2)), {
      case (_: Type, _: SType, _: SType, _: SType, _: SType, _: SType) => PCanonicalNDArray(PFloat64(true), 2, true).sType
    }) { case (r, cb, rt: SNDArrayPointer, rngState: SRNGStateValue, nRows: SInt64Value, nCols: SInt64Value, min, max, errorID) =>
      val result = rt.pType.constructUninitialized(FastIndexedSeq(SizeValueDyn(nRows.value), SizeValueDyn(nCols.value)), cb, r.region)
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      result.coiterateMutate(cb, r.region) { _ =>
        primitive(cb.memoize(rng.invoke[Double, Double, Double]("runif", min.asDouble.value, max.asDouble.value)))
      }
      result
    }

    registerSCode2("rand_int32", TRNGState, TInt32, TInt32, {
      case (_: Type, _: SType, _: SType) => SInt32
    }) { case (r, cb, rt, rngState: SRNGStateValue, n: SInt32Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      primitive(cb.memoize(rng.invoke[Int, Int]("nextInt", n.value)))
    }

    registerSCode2("rand_int64", TRNGState, TInt64, TInt64, {
      case (_: Type, _: SType, _: SType) => SInt64
    }) { case (r, cb, rt, rngState: SRNGStateValue, n: SInt64Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      primitive(cb.memoize(rng.invoke[Long, Long]("nextLong", n.value)))
    }

    registerSCode1("rand_int64", TRNGState, TInt64, {
      case (_: Type, _: SType) => SInt64
    }) { case (r, cb, rt, rngState: SRNGStateValue, errorID) =>
      primitive(rngState.rand(cb)(0))
    }

    registerSCode5("rand_norm_nd", TRNGState, TInt64, TInt64, TFloat64, TFloat64, TNDArray(TFloat64, Nat(2)), {
      case (_: Type, _: SType, _: SType, _: SType, _: SType, _: SType) => PCanonicalNDArray(PFloat64(true), 2, true).sType
    }) { case (r, cb, rt: SNDArrayPointer, rngState: SRNGStateValue, nRows: SInt64Value, nCols: SInt64Value, mean, sd, errorID) =>
      val result = rt.pType.constructUninitialized(FastIndexedSeq(SizeValueDyn(nRows.value), SizeValueDyn(nCols.value)), cb, r.region)
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      result.coiterateMutate(cb, r.region) { _ =>
        primitive(cb.memoize(rng.invoke[Double, Double, Double]("rnorm", mean.asDouble.value, sd.asDouble.value)))
      }
      result
    }

    registerSCode3("rand_norm", TRNGState, TFloat64, TFloat64, TFloat64, {
      case (_: Type, _: SType, _: SType, _: SType) => SFloat64
    }) { case (_, cb, rt, rngState: SRNGStateValue, mean: SFloat64Value, sd: SFloat64Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      primitive(cb.memoize(rng.invoke[Double, Double, Double]("rnorm", mean.value, sd.value)))
    }

    registerSCode2("rand_bool", TRNGState, TFloat64, TBoolean, {
      case (_: Type, _: SType, _: SType) => SBoolean
    }) { case (_, cb, rt, rngState: SRNGStateValue, p: SFloat64Value, errorID) =>
      val u = rand_unif(cb, rngState.rand(cb))
      primitive(cb.memoize(u < p.value))
    }

    registerSCode2("rand_pois", TRNGState, TFloat64, TFloat64, {
      case (_: Type, _: SType, _: SType) => SFloat64
    }) { case (_, cb, rt, rngState: SRNGStateValue, lambda: SFloat64Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      primitive(cb.memoize(rng.invoke[Double, Double]("rpois", lambda.value)))
    }

    registerSCode3("rand_pois", TRNGState, TInt32, TFloat64, TArray(TFloat64), {
      case (_: Type, _: SType, _: SType, _: SType) => PCanonicalArray(PFloat64(true)).sType
    }) { case (r, cb, SIndexablePointer(rt: PCanonicalArray), rngState: SRNGStateValue, n: SInt32Value, lambda: SFloat64Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      rt.constructFromElements(cb, r.region, n.value, deepCopy = false) { case (cb, _) =>
        IEmitCode.present(cb,
          primitive(cb.memoize(rng.invoke[Double, Double]("rpois", lambda.value)))
        )
      }
    }

    registerSCode3("rand_beta", TRNGState, TFloat64, TFloat64, TFloat64, {
      case (_: Type, _: SType, _: SType, _: SType) => SFloat64
    }) { case (_, cb, rt, rngState: SRNGStateValue, a: SFloat64Value, b: SFloat64Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      primitive(cb.memoize(rng.invoke[Double, Double, Double]("rbeta", a.value, b.value)))
    }

    registerSCode5("rand_beta", TRNGState, TFloat64, TFloat64, TFloat64, TFloat64, TFloat64, {
      case (_: Type, _: SType, _: SType, _: SType, _: SType, _: SType) => SFloat64
    }) { case (_, cb, rt, rngState: SRNGStateValue, a: SFloat64Value, b: SFloat64Value, min: SFloat64Value, max: SFloat64Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      val value = cb.newLocal[Double]("value", rng.invoke[Double, Double, Double]("rbeta", a.value, b.value))
      cb.whileLoop(value < min.value || value > max.value, {
        cb.assign(value, rng.invoke[Double, Double, Double]("rbeta", a.value, b.value))
      })
      primitive(value)
    }

    registerSCode3("rand_gamma", TRNGState, TFloat64, TFloat64, TFloat64, {
      case (_: Type, _: SType, _: SType, _: SType) => SFloat64
    }) { case (_, cb, rt, rngState: SRNGStateValue, a: SFloat64Value, scale: SFloat64Value, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)
      primitive(cb.memoize(rng.invoke[Double, Double, Double]("rgamma", a.value, scale.value)))
    }

    registerSCode2("rand_cat", TRNGState, TArray(TFloat64), TInt32, {
      case (_: Type, _: SType, _: SType) => SInt32
    }) { case (_, cb, rt, rngState: SRNGStateValue, weights: SIndexableValue, errorID) =>
      val len = weights.loadLength()
      val i = cb.newLocal[Int]("i", 0)
      val s = cb.newLocal[Double]("sum", 0.0)
      cb.whileLoop(i < len, {
        cb.assign(s, s + weights.loadElement(cb, i).get(cb, "rand_cat requires all elements of input array to be present").asFloat64.value)
        cb.assign(i, i + 1)
      })
      val r = cb.newLocal[Double]("r", rand_unif(cb, rngState.rand(cb)) * s)
      cb.assign(i, 0)
      val elt = cb.newLocal[Double]("elt")
      cb.loop { start =>
        cb.assign(elt, weights.loadElement(cb, i).get(cb, "rand_cat requires all elements of input array to be present").asFloat64.value)
        cb.ifx(r > elt && i < len, {
          cb.assign(r, r - elt)
          cb.assign(i, i + 1)
          cb.goto(start)
        })
      }
      primitive(i)
    }

    registerSCode3("shuffle_compute_num_samples_per_partition", TRNGState, TInt32, TArray(TInt32), TArray(TInt32),
      (_, _, _, _) => SIndexablePointer(PCanonicalArray(PInt32(true), false))
    ) { case (r, cb, rt, rngState: SRNGStateValue, initalNumSamplesToSelect: SInt32Value, partitionCounts: SIndexableValue, errorID) =>
      val rng = cb.emb.getThreefryRNG()
      rngState.copyIntoEngine(cb, rng)

      val totalNumberOfRecords = cb.newLocal[Int]("scnspp_total_number_of_records", 0)
      val resultSize: Value[Int] = partitionCounts.loadLength()
      val i = cb.newLocal[Int]("scnspp_index", 0)
      cb.forLoop(cb.assign(i, 0), i < resultSize, cb.assign(i, i + 1), {
        cb.assign(totalNumberOfRecords, totalNumberOfRecords + partitionCounts.loadElement(cb, i).get(cb).asInt32.value)
      })

      cb.ifx(initalNumSamplesToSelect.value > totalNumberOfRecords, cb._fatal("Requested selection of ", initalNumSamplesToSelect.value.toS,
        " samples from ", totalNumberOfRecords.toS, " records"))

      val successStatesRemaining = cb.newLocal[Int]("scnspp_success", initalNumSamplesToSelect.value)
      val failureStatesRemaining = cb.newLocal[Int]("scnspp_failure", totalNumberOfRecords - successStatesRemaining)

      val arrayRt = rt.asInstanceOf[SIndexablePointer]
      val (push, finish) = arrayRt.pType.asInstanceOf[PCanonicalArray].constructFromFunctions(cb, r.region, resultSize, false)

      cb.forLoop(cb.assign(i, 0), i < resultSize, cb.assign(i, i + 1), {
        val numSuccesses = cb.memoize(rng.invoke[Double, Double, Double, Double]("rhyper",
          successStatesRemaining.toD, failureStatesRemaining.toD, partitionCounts.loadElement(cb, i).get(cb).asInt32.value.toD).toI)
        cb.assign(successStatesRemaining, successStatesRemaining - numSuccesses)
        cb.assign(failureStatesRemaining, failureStatesRemaining - (partitionCounts.loadElement(cb, i).get(cb).asInt32.value - numSuccesses))
        push(cb, IEmitCode.present(cb, new SInt32Value(numSuccesses)))
      })

      finish(cb)
    }

  }
}
