package scalanlp.parser

/**
 * Holds the information for the marginals for a sentence
 *
 * @param scorer the specialization for a sentence.
 * @param inside inside chart
 * @param outside outside chart
 * @param partition the normalization constant aka inside score of the root aka probability of the sentence
 * @tparam Chart The kind of parse chart
 * @tparam L the label type
 * @tparam W the word type
 */
case class ChartMarginal[+Chart[X]<:ParseChart[X], L, W](scorer: DerivationScorer[L, W],
                                                         inside: Chart[L],
                                                         outside: Chart[L],
                                                         partition: Double) extends Marginal[L, W] {
  /**
   * Forest traversal that visits spans in a "bottom up" order.
   * @param spanVisitor
   */
  def visitPostorder(spanVisitor: DerivationVisitor[L]) {
    if(partition.isInfinite) throw new RuntimeException("No parse for " + words)
    val itop = inside.top

    // handle lexical
    for (i <- 0 until words.length) {
      for {
        aa <- lexicon.tagsForWord(words(i))
        a = grammar.labelIndex(aa)
        ref <- scorer.validLabelRefinements(i, i+ 1, a)
      } {
        val score:Double = scorer.scoreSpan(i, i+1, a, ref) + outside.bot(i, i+1, a, ref) - partition
        if (score != Double.NegativeInfinity) {
          //println(scorer.scoreSpan(i, i+1, a, ref), outside.bot(i, i+1, a, ref), partition)
          spanVisitor.visitSpan(i, i+1, a, ref, math.exp(score))
        }
      }
    }


    // handle binaries
    for {
      span <- 2 to inside.length
      begin <- 0 to (inside.length - span)
    } {
      val end = begin + span

      // I get a 20% speedup if i inline these arrays. so be it.
      val narrowRight = inside.top.narrowRight(begin)
      val narrowLeft = inside.top.narrowLeft(end)
      val wideRight = inside.top.wideRight(begin)
      val wideLeft = inside.top.wideLeft(end)

      val coarseNarrowRight = inside.top.coarseNarrowRight(begin)
      val coarseNarrowLeft = inside.top.coarseNarrowLeft(end)
      val coarseWideRight = inside.top.coarseWideRight(begin)
      val coarseWideLeft = inside.top.coarseWideLeft(end)

      for (a <- inside.bot.enteredLabelIndexes(begin, end); refA <- inside.bot.enteredLabelRefinements(begin, end, a)) {
        var i = 0
        val rules = grammar.indexedBinaryRulesWithParent(a)
        val spanScore = scorer.scoreSpan(begin, end, a, refA)
        val aScore = outside.bot.labelScore(begin, end, a, refA) + spanScore
        var count = 0.0
        if (!aScore.isInfinite)
          while(i < rules.length) {
            val r = rules(i)
            val b = grammar.leftChild(r)
            val c = grammar.rightChild(r)
            i += 1

            val narrowR:Int = coarseNarrowRight(b)
            val narrowL:Int = coarseNarrowLeft(c)

            val canBuildThisRule = if (narrowR >= end || narrowL < narrowR) {
              false
            } else {
              val trueX:Int = coarseWideLeft(c)
              val trueMin = if(narrowR > trueX) narrowR else trueX
              val wr:Int = coarseWideRight(b)
              val trueMax = if(wr < narrowL) wr else narrowL
              if(trueMin > narrowL || trueMin > trueMax) false
              else trueMin < trueMax + 1
            }

            if(canBuildThisRule) {
              val refinements = scorer.validRuleRefinementsGivenParent(begin, end, r, refA)
              var ruleRefIndex = 0
              while(ruleRefIndex < refinements.length) {
                val refR = refinements(ruleRefIndex)
                ruleRefIndex += 1
                val refB = scorer.leftChildRefinement(r, refR)
                val refC = scorer.rightChildRefinement(r, refR)
                val narrowR:Int = narrowRight(b)(refB)
                val narrowL:Int = narrowLeft(c)(refC)

                val feasibleSpan = if (narrowR >= end || narrowL < narrowR) {
                  0L
                } else {
                  val trueX:Int = wideLeft(c)(refC)
                  val trueMin = if (narrowR > trueX) narrowR else trueX
                  val wr:Int = wideRight(b)(refB)
                  val trueMax = if (wr < narrowL) wr else narrowL
                  if (trueMin > narrowL || trueMin > trueMax)  0L
                  else ((trueMin:Long) << 32) | ((trueMax + 1):Long)
                }
                var split = (feasibleSpan >> 32).toInt
                val endSplit = feasibleSpan.toInt // lower 32 bits
                while(split < endSplit) {
                  val bInside = itop.labelScore(begin, split, b, refB)
                  val cInside = itop.labelScore(split, end, c, refC)
                  if (!java.lang.Double.isInfinite(bInside + cInside)) {
                    val ruleScore = scorer.scoreBinaryRule(begin, split, end, r, refR)
                    val score = aScore + ruleScore + bInside + cInside - partition
                    val expScore = math.exp(score)
                    count += expScore
                    spanVisitor.visitBinaryRule(begin, split, end, r, refR, expScore)
                  }

                  split += 1
                }
              }
            }
          }
        spanVisitor.visitSpan(begin, end, a, refA, count)
      }
    }

    // Unaries
    for {
      span <- 1 to words.length
      begin <- 0 to (words.length - span)
      end = begin + span
      a <- inside.top.enteredLabelIndexes(begin, end)
      refA <- inside.top.enteredLabelRefinements(begin, end, a)
    } {
      val aScore = outside.top.labelScore(begin, end, a, refA)
      for (r <- grammar.indexedUnaryRulesWithParent(a); refR <- scorer.validRuleRefinementsGivenParent(begin, end, r, refA)) {
        val b = grammar.child(r)
        val refB = scorer.childRefinement(r, refR)
        val bScore = inside.bot.labelScore(begin, end, b, refB)
        val rScore = scorer.scoreUnaryRule(begin, end, r, refR)
        val prob = math.exp(bScore + aScore + rScore - partition)
        if (prob > 0)
          spanVisitor.visitUnaryRule(begin, end, r, refR, prob)
      }
    }
  }
}

object ChartMarginal {
  def fromSentence[L, W](grammar: DerivationScorer.Factory[L, W], sent: Seq[W]) = {
    val builder = ChartBuilder(grammar)
    builder.charts(sent)
  }

  def fromSentence[L, W](scorer: DerivationScorer[L, W], sent: Seq[W]) = {
    val builder = ChartBuilder(DerivationScorerFactory.oneOff(scorer))
    builder.charts(sent)
  }
}
