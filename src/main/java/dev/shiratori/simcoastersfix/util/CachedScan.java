package dev.shiratori.simcoastersfix.util;

import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;

public record CachedScan(long gameTime, long ttl, CoasterCartPlotScan.BearingScanResult result) {
}
