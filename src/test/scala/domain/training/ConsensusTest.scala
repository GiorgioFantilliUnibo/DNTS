package domain.training

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import domain.network.{Activations, Layer, Network}
import domain.data.LinearAlgebra.{Matrix, Vector}
import domain.training.Consensus.*
import domain.training.consensus.{ConsensusMetric, ConsensusOps}

class ConsensusTest extends AnyFunSuite with Matchers {

  private final val netA = Network(List(
    Layer(Matrix.fill(1, 1)(0.0), Vector.fromList(List(0.0)), Activations.Sigmoid)
  ))

  private final val netB = Network(List(
    Layer(Matrix.fill(1, 1)(1.0), Vector.fromList(List(1.0)), Activations.Sigmoid)
  ))


  test("ConsensusOps.weightedAverageModels should correctly apply weights") {
    val weightedNet = ConsensusOps.weightedAverageModels(netA, 0.9, netB, 0.1)

    weightedNet.layers.head.weights(0)(0) shouldBe (0.1 +- 0.0001)
  }

  test("Model.mergeWith should apply a 95%-5% weighted average when maturity difference is huge") {
    val healthyModel = domain.network.Model(netA, Nil, maturity = 100)
    val recoveredModel = domain.network.Model(netB, Nil, maturity = 5)

    val mergedModel = healthyModel mergeWith recoveredModel

    val mergedWeight = mergedModel.network.layers.head.weights(0)(0)
    mergedWeight shouldBe (0.05 +- 0.0001)
    mergedModel.maturity shouldBe 100
  }

  test("Network.divergenceFrom should compute correct distance using default metric") {
    import domain.training.consensus.ConsensusMetric.given

    netA divergenceFrom netB shouldBe 1.0
  }

  test("Network.divergenceFrom should be zero for identical networks") {
    import domain.training.consensus.ConsensusMetric.given

    netA divergenceFrom netA shouldBe 0.0
  }

  test("averageGradients should return the arithmetic mean of gradients") {
    val grad1 = NetworkGradient(List(
      LayerGradient(Matrix.fill(1, 1)(10.0), Vector.fromList(List(10.0)))
    ))

    val grad2 = NetworkGradient(List(
      LayerGradient(Matrix.fill(1, 1)(20.0), Vector.fromList(List(20.0)))
    ))

    val result = Consensus.averageGradients(List(grad1, grad2))

    result.layers.head.wGrad(0)(0) shouldBe 15.0
  }
}
