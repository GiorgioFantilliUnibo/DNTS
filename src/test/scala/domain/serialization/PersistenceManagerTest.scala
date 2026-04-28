package domain.serialization

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

import scala.util.Success
import java.nio.file.{Files, Paths}
import domain.network.{Activations, Model}
import domain.network.ModelBuilder
import domain.network.Feature
import domain.data.{LabeledPoint2D, Label, Point2D}

import domain.network.Activations.given

import domain.serialization.PersistenceManager.*
import domain.serialization.NetworkSerializers.given
import domain.serialization.LinearAlgebraSerializers.given
import domain.serialization.ModelSerializers.given
import domain.serialization.DatasetSerializers.given


class PersistenceManagerTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val modelPath = "persistence_test_model.bin"
  private val datasetPath = "persistence_test_dataset.bin"

  private val originalModel: Model = ModelBuilder
    .fromInputs(Feature.X, Feature.Y)
    .addLayer(neurons = 4, activation = Activations.Relu)
    .withMaturity(42)
    .withSeed(12345L)
    .build()

  private val originalDataset: List[LabeledPoint2D] = List(
    LabeledPoint2D(Point2D(1.0, 2.0), Label.Positive),
    LabeledPoint2D(Point2D(-1.0, 0.0), Label.Negative)
  )

  override def afterAll(): Unit = {
    Files.deleteIfExists(Paths.get(modelPath))
    Files.deleteIfExists(Paths.get(datasetPath))
  }

  test("PersistenceManager should preserve the structural integrity of the MODEL") {
    originalModel.saveToFile(modelPath) shouldBe a[Success[_]]

    val loaded = loadFromFile[Model](modelPath)
    loaded shouldBe Success(originalModel)
    
    val m = loaded.get
    m.network.layers.head.weights shouldEqual originalModel.network.layers.head.weights
  }

  test("PersistenceManager should preserve the integrity of the DATASET (Labeled Points)") {
    originalDataset.saveToFile(datasetPath) shouldBe a[Success[_]]

    val loaded = loadFromFile[List[LabeledPoint2D]](datasetPath)
    loaded shouldBe Success(originalDataset)

    val ds = loaded.get
    ds.head.label shouldBe originalDataset.head.label
    ds.last.point.x shouldBe originalDataset.last.point.x
  }

}
