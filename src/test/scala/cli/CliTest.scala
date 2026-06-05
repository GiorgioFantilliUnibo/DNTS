package cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import domain.authentication.NodeRole

class CliTest extends AnyFunSuite with Matchers:
  
  test("CliParser should return Help result when --help is present"):
    CliParser.parse(List("--role", "seed", "--help")) shouldBe ParseResult.Help

  test("CliParser should correctly parse a complete Seed command"):
    val args = List("--role", NodeRole.Seed.toString, "--cluster", "TestCluster", "--config", "simulation.conf", "--port", "2551")
    val expected = ParseResult.Success(
      CliOptions(role = Some(NodeRole.Seed), cluster = Some("TestCluster"), configFile = Some("simulation.conf"), port = Some(2551))
    )

    CliParser.parse(args) shouldBe expected

  test("CliParser should correctly parse a complete Client command"):
    val args = List("--role", NodeRole.Client.toString, "--cluster", "TestCluster", "--seedAddress", "127.0.0.1:2551", "--port", "2552")
    val expected = ParseResult.Success(
      CliOptions(role = Some(NodeRole.Client), cluster = Some("TestCluster"), seedAddress = Some("127.0.0.1:2551"), port = Some(2552))
    )

    CliParser.parse(args) shouldBe expected

  test("CliParser should fail parsing on invalid role strings"):
    val args = List("--role", "magician")

    CliParser.parse(args) shouldBe a [ParseResult.Failure]

  test("CliParser should fail parsing on non-numeric ports"):
    val args = List("--port", "xyz")
    
    CliParser.parse(args) shouldBe a [ParseResult.Failure]
  
  
  test("CliOptions should validate successfully a correct Seed configuration"):
    val opts = CliOptions(role = Some(NodeRole.Seed), cluster = Some("TestCluster"), configFile = Some("simulation.conf"), port = Some(2551))
    
    opts.validate shouldBe a [Right[?, ?]]

  test("CliOptions should fail validation for Client missing IP"):
    val opts = CliOptions(role = Some(NodeRole.Client), cluster = Some("TestCluster"), port = Some(2552))
    
    opts.validate shouldBe a [Left[?, ?]]

  test("CliOptions should fail validation for Client missing Port"):
    val opts = CliOptions(role = Some(NodeRole.Client), cluster = Some("TestCluster"), seedAddress = Some("1.1.1.1:2551"))
    
    opts.validate shouldBe a [Left[?, ?]]

  test("CliOptions should fail validation for missing Cluster"):
    val opts = CliOptions(role = Some(NodeRole.Seed), configFile = Some("simulation.conf"), port = Some(2551))
    
    opts.validate shouldBe a [Left[?, ?]]
    