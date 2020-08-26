package shopping.cart

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import akka.grpc.GrpcClientSettings
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import org.slf4j.LoggerFactory
import shopping.order.proto.{ ShoppingOrderService, ShoppingOrderServiceClient }

import scala.concurrent.Await
import scala.concurrent.duration._

// tag::start-grpc[]
object Main {

  def main(args: Array[String]): Unit = {
    val system = ActorSystem[Nothing](Guardian(), "Cart")
    // end::start-grpc[]
    if (system.settings.config.getBoolean("shopping-cart.create-tables")) {
      createTables(system)
    }
  }

  def createTables(system: ActorSystem[_]): Unit = {
    // TODO: In production the keyspace and tables should not be created automatically.
    // ok to block here, main thread
    Await.result(CassandraProjection.createOffsetTableIfNotExists()(system), 30.seconds)

    // use same keyspace for the item_popularity table as the offset store
    val keyspace = system.settings.config.getString("akka.projection.cassandra.offset-store.keyspace")
    val session = CassandraSessionRegistry(system).sessionFor("akka.projection.cassandra.session-config")
    Await.result(ItemPopularityRepositoryImpl.createItemPopularityTable(session, keyspace), 30.seconds)

    LoggerFactory.getLogger("shopping.cart.Main").info("Created keyspace [{}] and tables", keyspace)
  }

}

// tag::start-grpc[]

object Guardian {
  def apply(): Behavior[Nothing] = {
    Behaviors.setup[Nothing](context => new Guardian(context))
  }
}

class Guardian(context: ActorContext[Nothing]) extends AbstractBehavior[Nothing](context) {
  val system = context.system
  // end::start-grpc[]

  startAkkaManagement()

  val session = CassandraSessionRegistry(system).sessionFor("akka.projection.cassandra.session-config") // <1>
  // use same keyspace for the item_popularity table as the offset store
  val itemPopularityKeyspace = system.settings.config.getString("akka.projection.cassandra.offset-store.keyspace")
  val itemPopularityRepository =
    new ItemPopularityRepositoryImpl(session, itemPopularityKeyspace)(system.executionContext) // <2>

  val grpcInterface = system.settings.config.getString("shopping-cart.grpc.interface")
  val grpcPort = system.settings.config.getInt("shopping-cart.grpc.port")
  ShoppingCartServer.start(grpcInterface, grpcPort, system, itemPopularityRepository)

  ShoppingCart.init(system)
  // tag::ItemPopularityProjection[]
  ItemPopularityProjection.init(system, itemPopularityRepository) // <3>
  // end::ItemPopularityProjection[]

  // tag::PublishEventsProjection[]
  PublishEventsProjection.init(system)
  // end::PublishEventsProjection[]

  val orderService = orderServiceClient(system)
  SendOrderProjection.init(system, orderService)
  // end::SendOrderProjection[]

  // can be overridden in tests
  protected def orderServiceClient(system: ActorSystem[_]): ShoppingOrderService = {
    val orderServiceClientSettings =
      GrpcClientSettings.usingServiceDiscovery("order-service-grpc")(system).withTls(false)
    val orderServiceClient = ShoppingOrderServiceClient(orderServiceClientSettings)(system)
    orderServiceClient
  }

  // can be overridden in tests
  protected def startAkkaManagement(): Unit = {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()
  }

  override def onMessage(msg: Nothing): Behavior[Nothing] = {
    this
  }
}