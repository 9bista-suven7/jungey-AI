package dev.suven.jungey.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.net.spi.InetAddressResolver.LookupPolicy.IPV4;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV6;
import static java.net.spi.InetAddressResolver.LookupPolicy.IPV6_FIRST;

/**
 * Looks names up one address family at a time.
 *
 * <p>The system resolver asks for a name's IPv4 and IPv6 addresses at once, from one socket.
 * Some routers and resolvers drop one of the two answers, and the lookup then waits five
 * seconds before asking again. On the machine this was written on, looking up
 * en.wikipedia.org took 5.2 seconds that way and 0.2 and 0.5 seconds asked in turn - and
 * every answer that needs the network paid it again once Java's 30-second cache had lapsed.
 *
 * <p>Registered in META-INF/services, so it covers every connection Jungey makes. Either
 * family failing on its own is fine: an IPv6-only network still gets its addresses.
 */
public final class SplitResolver extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver system = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                int wants = policy.characteristics();
                if ((wants & IPV4) == 0 || (wants & IPV6) == 0) return system.lookupByName(host, policy);

                boolean sixFirst = (wants & IPV6_FIRST) != 0;
                List<InetAddress> found = new ArrayList<>();
                UnknownHostException failure = null;
                for (int family : sixFirst ? new int[]{IPV6, IPV4} : new int[]{IPV4, IPV6}) {
                    try {
                        system.lookupByName(host, LookupPolicy.of(family)).forEach(found::add);
                    } catch (UnknownHostException e) {
                        if (failure == null) failure = e;
                    }
                }
                if (found.isEmpty()) throw failure != null ? failure : new UnknownHostException(host);
                return found.stream();
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return system.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return "jungey-split";
    }
}
