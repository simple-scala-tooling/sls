package fixture
trait Greeter {
  def greet(): String
}
class FriendlyGreeter extends Greeter {
  override def greet(): String = "Hi!"
}
