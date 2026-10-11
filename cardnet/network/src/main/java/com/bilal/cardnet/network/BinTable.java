package com.bilal.cardnet.network;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Which issuer a card number belongs to: the longest configured prefix wins. */
public final class BinTable {

    private final List<NetworkConfig.Route> routes;

    public BinTable(List<NetworkConfig.Route> routes) {
        this.routes = routes.stream().sorted(Comparator.comparingInt((NetworkConfig.Route r) -> r.prefix().length()).reversed()).toList();
    }

    public Optional<NetworkConfig.Route> lookup(String pan) {
        if (pan == null) {
            return Optional.empty();
        }
        return routes.stream().filter(r -> pan.startsWith(r.prefix())).findFirst();
    }
}
