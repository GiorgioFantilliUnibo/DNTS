package domain.training

import domain.network.Network
import domain.network.Model
import domain.training.consensus.{ConsensusOps, ConsensusMetric}

/**
 * Facade object providing high-level consensus and synchronization primitives for [[Network]]s.
 */
object Consensus:

  extension (m1: Model)
    /**
     * Computes the [[Model]] averaging considering the peer maturity.
     *
     * @param m2 The other [[Model]] to average with. Must have the exact same topology.
     * @return A new [[Model]] updated instance.
     */
    infix def mergeWith(m2: Model): Model =
      val maturityDelta = Math.abs(m1.maturity - m2.maturity)
      val threshold = 10

      val (w1, w2) = if maturityDelta > threshold then
        if m1.maturity > m2.maturity then (0.95, 0.05) else (0.05, 0.95)
      else
        (0.5, 0.5)

      val mergedNetwork = ConsensusOps.weightedAverageModels(m1.network, w1, m2.network, w2)

      val newMaturity = Math.max(m1.maturity, m2.maturity)

      Model(mergedNetwork, m1.features, newMaturity)

  
  extension (n1: Network)
    /**
     * Calculates the quantitative difference between this network and another.
     *
     * @param n2     The network to compare against.
     * @param metric The implicit strategy used to calculate the distance.
     * @return A scalar double representing the magnitude of the difference.
     */
    infix def divergenceFrom(n2: Network)(using metric: ConsensusMetric): Double =
      metric.divergence(n1, n2)

  /**
   * Exports the gradient aggregation utility for direct access.
   * Allows averaging a list of [[NetworkGradient]]s.
   */
  export ConsensusOps.averageGradients
