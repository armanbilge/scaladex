package scaladex.client

import org.scalajs.dom.Node
import org.scalajs.dom.document
import org.scalajs.dom.raw.Element
import org.scalajs.dom.raw.HTMLInputElement
import scaladex.core.api.AutocompletionParams

object Dom {
  def getSearchRequest: Option[AutocompletionParams] =
    for (query <- getSearchQuery)
      yield AutocompletionParams(
        query = query,
        you = getElementById("you").map(_.asInput.value).contains("✓"),
        topics = getSearchFilter("topics"),
        languages = getSearchFilter("languages"),
        platforms = getSearchFilter("platforms"),
        contributingSearch = getElementById("contributing-search")
          .map(_.asInput.value)
          .contains("true")
      )

  def getSearchQuery: Option[String] =
    getSearchInput.map(_.value).filter(_.length > 0)

  def getSearchInput: Option[HTMLInputElement] = getSearchBox.map(_.asInput)

  def getResultList: Option[Element] = getElementById("list-result")

  def getSearchBox: Option[Element] = getElementById("search")

  def getElementById(id: String): Option[Element] =
    Option(document.getElementById(id))

  private def getSearchFilter(name: String): Seq[String] =
    getElementsByName(name)
      .map(_.asInput)
      .filter(_.checked)
      .map(_.value)

  private def getElementsByName(name: String): Seq[Element] = {
    val nodeList = document.getElementsByName(name)
    0.until(nodeList.length).map(nodeList(_).asElement)
  }

  implicit class ElementOps(e: Element) {
    def asInput: HTMLInputElement = as[HTMLInputElement]
    def as[A <: Element]: A = e.asInstanceOf[A]
  }

  private implicit class NodeOps(n: Node) {
    def asElement: Element = n.asInstanceOf[Element]
  }
}
