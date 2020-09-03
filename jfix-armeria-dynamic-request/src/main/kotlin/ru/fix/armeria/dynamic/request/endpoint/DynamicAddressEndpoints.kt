package ru.fix.armeria.dynamic.request.endpoint

import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import ru.fix.dynamic.property.api.DynamicProperty
import java.util.concurrent.CompletableFuture

class SocketAddress(
    val host: String,
    val port: Int
) {
    fun asEndpoint(): Endpoint = Endpoint.of(host, port)
}

object DynamicAddressEndpoints {

    @JvmStatic
    fun dynamicAddressEndpoint(addressProperty: DynamicProperty<SocketAddress>): EndpointGroup =
        DynamicAddressEndpoint(addressProperty)

    @JvmStatic
    fun dynamicAddressListEndpointGroup(
        addressListProperty: DynamicProperty<List<SocketAddress>>,
        endpointSelectionStrategy: EndpointSelectionStrategy = defaultEndpointSelectionStrategy
    ): EndpointGroup =
        DynamicAddressListEndpointGroup(addressListProperty, endpointSelectionStrategy)

}

private val defaultEndpointSelectionStrategy = EndpointSelectionStrategy.roundRobin()

private abstract class BaseDynamicPropertyEndpointGroup<T : Any>(
    property: DynamicProperty<T>,
    asEndpoints: T.() -> List<Endpoint>,
    endpointSelectionStrategy: EndpointSelectionStrategy = defaultEndpointSelectionStrategy
) : DynamicEndpointGroup(endpointSelectionStrategy) {
    private val propertySubscription = property.createSubscription()

    init {
        propertySubscription.setAndCallListener { _, newValue ->
            val newEndpoints = newValue.asEndpoints()
            setEndpoints(newEndpoints)
        }
    }

    override fun doCloseAsync(future: CompletableFuture<*>) {
        propertySubscription.close()
        super.doCloseAsync(future)
    }
}

private class DynamicAddressEndpoint(addressProperty: DynamicProperty<SocketAddress>) :
    BaseDynamicPropertyEndpointGroup<SocketAddress>(addressProperty, asEndpoints = { listOf(asEndpoint()) })

private class DynamicAddressListEndpointGroup(
    addressListProperty: DynamicProperty<List<SocketAddress>>,
    endpointSelectionStrategy: EndpointSelectionStrategy = defaultEndpointSelectionStrategy
) :
    BaseDynamicPropertyEndpointGroup<List<SocketAddress>>(
        addressListProperty,
        asEndpoints = { this.map { it.asEndpoint() } },
        endpointSelectionStrategy = endpointSelectionStrategy
    )

