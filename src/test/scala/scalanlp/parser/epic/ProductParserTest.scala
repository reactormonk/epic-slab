package scalanlp.parser
package epic

import org.junit.runner.RunWith;
import org.scalatest._;
import org.scalatest.junit._


/**
 *
 * @author dlwh
 */
@RunWith(classOf[JUnitRunner])
class ProductParserTest extends ParserTestHarness with FunSuite {

  test("basic test") {
    val factory = ParserTestHarness.simpleParser.builder.grammar
    val product = ProductParser.fromChartParsers(factory.grammar,
      factory.lexicon, factory)

    val rprod = evalParser(getTestTrees(), product)
    println(rprod, evalParser(getTestTrees(), ParserTestHarness.simpleParser));
    assert(rprod.f1 > 0.6, rprod);
  }

  test("two parsers test") {
    val factory = ParserTestHarness.simpleParser.builder.grammar
    val product = ProductParser.fromChartParsers(factory.grammar,
      factory.lexicon, factory, factory)

    val rprod = evalParser(getTestTrees(), product)
    println(rprod, evalParser(getTestTrees(), ParserTestHarness.simpleParser));
    assert(rprod.f1 > 0.6, rprod);
  }
}

