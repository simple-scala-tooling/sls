package fixture
import scala.annotation.unused
class VisibilityExample {
  // `@unused` keeps scalafix's RemoveUnused from stripping this — it exists only so the indexer can
  // observe a private member's visibility.
  @unused private val secret: Int = 42
  protected def helper(): Unit     = ()
  def publicMethod(): String       = "visible"
}
