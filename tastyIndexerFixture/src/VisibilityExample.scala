package fixture
class VisibilityExample {
  private val secret: Int = 42
  protected def helper(): Unit = ()
  def publicMethod(): String = "visible"
}
