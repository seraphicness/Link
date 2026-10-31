package io.linkmc.server;

import io.linkmc.api.LinkApi;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LinkTravelCommandTestSuite {
    LinkApi.Network network;
    Player player;
    UUID id;

    @BeforeEach void setup() {
        network = mock(LinkApi.Network.class);
        player = mock(Player.class);
        id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("link.warp")).thenReturn(true);
        when(network.requestTravel(eq(id), any(UUID.class), nullable(LinkApi.Destination.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
    }
    LinkTravelCommand command(String name, boolean journal) {
        return new LinkTravelCommand(name, network, journal, Runnable::run, error -> {});
    }
    @Test void spawnRequestsAllocatedDestination() {
        command("spawn", true).execute(player, "spawn", new String[0]);
        verify(network).requestTravel(eq(id), any(UUID.class), isNull());
    }
    @Test void warpResolvesDestinationBeforeTravel() {
        var destination = new LinkApi.Destination("node-b", "lobby", 1, 65, 2, 0, 0);
        when(network.warp("shop")).thenReturn(CompletableFuture.completedFuture(Optional.of(destination)));
        command("warp", true).execute(player, "warp", new String[]{"shop"});
        verify(network).requestTravel(eq(id), any(UUID.class), eq(destination));
    }
    @Test void missingWarpNeverFallsBackToSpawn() {
        when(network.warp("missing")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        command("warp", true).execute(player, "warp", new String[]{"missing"});
        verify(network, never()).requestTravel(any(), any(), any());
        verify(player).sendMessage("등록되지 않은 워프입니다.");
    }
    @Test void permissionDenialDoesNotAccessDatabase() {
        when(player.hasPermission("link.warp")).thenReturn(false);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::permissionMessage).thenReturn(net.kyori.adventure.text.Component.text("No permission"));
            command("warp", true).execute(player, "warp", new String[]{"shop"});
            verifyNoInteractions(network);
        }
    }
    @Test void consoleInvalidArgumentsAndToolsModeDoNotTravel() {
        command("spawn", true).execute(mock(CommandSender.class), "spawn", new String[0]);
        command("spawn", true).execute(player, "spawn", new String[]{"extra"});
        command("warp", true).execute(player, "warp", new String[0]);
        command("spawn", false).execute(player, "spawn", new String[0]);
        verifyNoInteractions(network);
    }
    @Test void databaseFailureIsReportedWithoutLeakingDetails() {
        when(network.requestTravel(eq(id), any(UUID.class), isNull()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("secret database detail")));
        command("spawn", true).execute(player, "spawn", new String[0]);
        verify(player).sendMessage("이동 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        verify(player, never()).sendMessage(contains("secret"));
    }
}
