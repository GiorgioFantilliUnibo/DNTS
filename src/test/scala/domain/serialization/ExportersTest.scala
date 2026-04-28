package domain.serialization

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import domain.network.{Activations, Feature, ModelBuilder, Model}
import domain.serialization.Exporters.given

class ExportersTest extends AnyFunSuite with Matchers {

  def createModel(seed: Long): Model =
    ModelBuilder.fromInputs(Feature.X, Feature.Y)
      .addLayer(2, Activations.Relu)
      .withSeed(seed)
      .build()

  final val dummyModel = createModel(1)

  test("Model export should start with 'model' block and contain features") {
    val json = dummyModel.jsonExport
    json should startWith ("model {")
    json should include ("features = [\"X\", \"Y\"]")
  }

  test("Model export should contain 'hidden-layers' section") {
    val json = dummyModel.jsonExport
    json should include ("hidden-layers =")
  }

  test("Model export contains technical network fields") {
    val json = dummyModel.jsonExport

    json should include ("\"layer_index\":")
    json should include ("\"activation\":")
    json should include ("\"neurons_count\":")
  }

  test("Model export contains weight and bias parameters") {
    val json = dummyModel.jsonExport

    json should include ("\"parameters\":")
    json should include ("\"biases\":")
    json should include ("\"weights\":")
  }
}