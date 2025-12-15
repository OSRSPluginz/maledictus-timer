package com.osrspluginz.maledictus;

import com.google.inject.Provides;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.swing.SwingUtilities; // Added for manual timer update
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@PluginDescriptor(
        name = "maledictus-timer",
        description = "Tracks Maledictus spawns, predicts next spawn, and shows world list readiness. Provides overlay and panel features.",
        tags = {"pvp", "pk", "pvm", "revenants", "maledictus", "wildy", "wilderness", "rev caves"}
)
public class MaledictusPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(MaledictusPlugin.class);

    // --- CONSTANTS ---
    public static final Duration RESET_TIMER = Duration.ofMinutes(45);
    public static final int MALEDICTUS_ID = 11246;
    private static final int DISPLAY_SWITCHER_MAX_ATTEMPTS = 10;

    // Time threshold for skull colors (in seconds)
    public static final long TIME_RED_THRESHOLD_SECS = Duration.ofMinutes(15).getSeconds(); // 15 mins (900s)

    private net.runelite.api.World quickHopTargetWorld;
    int displaySwitcherAttempts = 0;

    // --- INJECTS ---
    @Inject private OverlayManager overlayManager;
    @Inject private com.osrspluginz.maledictus.MaledictusOverlay overlay;
    @Inject private com.osrspluginz.maledictus.MaledictusConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private Client client;
    @Inject private ConfigManager configManager;
    @Inject private com.osrspluginz.maledictus.MaledictusPanel panel;
    @Inject private ClientThread clientThread;
    @Inject private WorldService worldService;
    @Inject private ChatMessageManager chatMessageManager;

    private NavigationButton navButton;
    private final Map<Integer, WorldTimer> worldTimers = new HashMap<>();

    // Cached skull icons
    private BufferedImage skullWhite;
    private BufferedImage skullRed;
    private BufferedImage skullPanel;

    @Provides
    com.osrspluginz.maledictus.MaledictusConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(com.osrspluginz.maledictus.MaledictusConfig.class);
    }

    @Override
    protected void startUp()
    {
        // Image loading paths
        skullWhite = ImageUtil.loadImageResource(getClass(), "/skullwhite.png");
        skullRed = ImageUtil.loadImageResource(getClass(), "/skullred.png");
        skullPanel = ImageUtil.loadImageResource(getClass(), "/skullpanel.png");

        overlayManager.add(overlay);

        navButton = NavigationButton.builder()
                .tooltip("Maledictus Tracker")
                .icon(skullPanel)
                .panel(panel)
                .priority(10)
                .build();
        clientToolbar.addNavigation(navButton);

        // --- Initialization: Dynamic World Loading ---
        loadWorldList();

        log.info("Maledictus Timer started.");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);

        if (navButton != null)
            clientToolbar.removeNavigation(navButton);

        worldTimers.clear();
        skullWhite = null;
        skullRed = null;
        skullPanel = null;

        log.info("Maledictus Timer stopped.");
    }

    /**
     * Dynamically fetches the world list from the client and populates the timer map
     * with valid Members worlds. This replaces the hardcoded array.
     */
    private void loadWorldList()
    {
        net.runelite.api.World[] worlds = client.getWorldList();

        if (worlds == null)
        {
            return; // Client hasn't loaded worlds yet; we will retry on GameStateChanged
        }

        int count = 0;
        for (net.runelite.api.World world : worlds)
        {
            EnumSet<WorldType> types = world.getTypes();

            // Filter: Must be Members, and NOT a temporary game mode (PVP Arena, Quest Speedrun, Beta, etc)
            if (types.contains(WorldType.MEMBERS)
                    && !types.contains(WorldType.QUEST_SPEEDRUNNING)
                    && !types.contains(WorldType.PVP_ARENA)
                    && !types.contains(WorldType.NOSAVE_MODE)
                    && !types.contains(WorldType.TOURNAMENT_WORLD)
                    && !types.contains(WorldType.FRESH_START_WORLD)) // Optional: exclude Fresh Start if desired
            {
                // Verify the world is not null/offline and add if missing
                if (worldTimers.putIfAbsent(world.getId(), new WorldTimer(world.getId(), Instant.MIN)) == null)
                {
                    count++;
                }
            }
        }

        if (count > 0)
        {
            log.debug("Loaded {} new member worlds.", count);
        }
    }

    // --- CONSOLE MESSAGE HELPER ---

    private void sendConsoleMessage(String message) {
        String chatMessage = new ChatMessageBuilder()
                .append(ChatColorType.NORMAL)
                .append("Maledictus Hopper: ")
                .append(ChatColorType.HIGHLIGHT)
                .append(message)
                .build();

        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(chatMessage)
                .build());
    }

    // --- Timer Management & Game Events ---

    /**
     * Sets the next **eligibility** time for Maledictus to spawn, which is
     * 45 minutes after the current spawn announcement (spawnTime).
     * @param world The world ID where the spawn occurred.
     * @param spawnTime The Instant the spawn announcement was received.
     */
    public void setMaledictusEligibility(int world, Instant spawnTime)
    {
        // Calculate the next time the spawn chance can accumulate (Spawn Time + 45 minutes)
        Instant nextEligibility = spawnTime.plus(RESET_TIMER);
        worldTimers.put(world, new WorldTimer(world, nextEligibility));
        log.info("Maledictus spawned on W{}. Next eligibility for spawn begins at {}", world, nextEligibility);
    }

    /**
     * Manually sets a timer for a specific world based on minutes remaining.
     * @param worldId The world number.
     * @param minutesRemaining Minutes left until eligible (e.g. 45 or 13).
     */
    public void setManualTimer(int worldId, int minutesRemaining)
    {
        // Calculate eligibility time based on minutes remaining from NOW.
        Instant nextEligibility = Instant.now().plus(Duration.ofMinutes(minutesRemaining));

        worldTimers.put(worldId, new WorldTimer(worldId, nextEligibility));

        // Force the panel to redraw immediately
        SwingUtilities.invokeLater(() -> panel.updatePanel());
    }

    @Schedule(
            period = 1,
            unit = ChronoUnit.SECONDS,
            asynchronous = true
    )
    public void updateTimers()
    {
        if (panel.isVisible())
        {
            panel.updatePanel();
        }
    }

    // --- TRACKING MALEDICTUS SPAWN (Timer Reset) ---

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        // Check for Maledictus spawn announcement
        if (event.getType() == ChatMessageType.GAMEMESSAGE)
        {
            // Use contains to correctly capture the message regardless of the location text that follows
            if (event.getMessage().contains("A superior revenant has been awoken"))
            {
                if (client.getGameState() == GameState.LOGGED_IN)
                {
                    int currentWorld = client.getWorld();
                    // The moment the spawn is announced, the 45-minute prevention timer begins.
                    setMaledictusEligibility(currentWorld, Instant.now());
                }
            }
        }

        // World hop block logic
        if (event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }
        if (event.getMessage().equals("Please finish what you're doing before using the World Switcher."))
        {
            sendConsoleMessage("Hop blocked by game: Please stop what you are doing (e.g. combat, skilling) and try again.");
            // We intentionally do not call resetQuickHopper() here so the hop retries on the next tick
        }
    }

    // --- WorldTimer Static Inner Class (Data Model) ---
    @Value
    public static class WorldTimer
    {
        private final int world;
        private final Instant nextEligibility; // Renamed for clarity

        public WorldTimer(int world, Instant nextEligibility)
        {
            this.world = world;
            this.nextEligibility = nextEligibility;
        }

        public int getWorld()
        {
            return world;
        }

        public Instant getNextSpawn() // Kept method name for interface consistency
        {
            return nextEligibility;
        }

        /**
         * Returns remaining seconds until eligible to spawn. Returns Long.MAX_VALUE if no data is observed.
         */
        public long secondsLeft()
        {
            if (getNextSpawn() == Instant.MIN)
            {
                return Long.MAX_VALUE;
            }
            return Instant.now().until(getNextSpawn(), ChronoUnit.SECONDS);
        }

        /**
         * Returns the formatted time string.
         * For Eligible/Active (remaining <= 0), it shows only the +time elapsed.
         */
        public String getDisplayText()
        {
            if (getNextSpawn() == Instant.MIN)
            {
                return "No Data"; // Display "No Data" for unobserved worlds
            }

            long remaining = secondsLeft();
            long absRemaining = Math.abs(remaining);

            long hours = absRemaining / 3600;
            long minutes = (absRemaining % 3600) / 60;
            long seconds = absRemaining % 60;

            String timeStr = (hours > 0)
                    ? String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    : String.format("%02d:%02d", minutes, seconds);

            // If remaining <= 0, the prevention period is over, and the boss is ELIGIBLE/ACTIVE
            if (remaining <= 0)
            {
                // Display ONLY the time since eligibility began (negative seconds)
                return String.format("+%s", timeStr);
            }

            // Display countdown to eligibility
            return timeStr;
        }

        /**
         * Final simplified skull logic: 45-15m = White, 15-0m = Red, <=0m = Panel Skull (Eligible/Active), No Data = White.
         */
        public BufferedImage getSkullIcon(MaledictusPlugin plugin)
        {
            // Worlds with 'No Data' use the white skull
            if (getNextSpawn() == Instant.MIN)
            {
                return plugin.getSkullWhite();
            }

            long remaining = secondsLeft();

            // 1. Eligible/Active (remaining <= 0) - This is the "cyan skull" state
            if (remaining <= 0)
            {
                return plugin.getSkullPanel();
            }

            final long TIME_RED_THRESHOLD_SECS = MaledictusPlugin.TIME_RED_THRESHOLD_SECS;

            // 2. 0 to 15 mins left in prevention period (Red Skull)
            if (remaining <= TIME_RED_THRESHOLD_SECS)
            {
                return plugin.getSkullRed();
            }

            // 3. 15 mins to 45 mins left (White Skull)
            return plugin.getSkullWhite();
        }
    }

    // --- Public Getters/Setters ---

    /**
     * Gets the timer for a world. If the world is the local player's current world and
     * is not tracked, a temporary 'No Data' timer is created and returned on the fly
     * to support the overlay and panel showing status for the current world.
     */
    public WorldTimer getWorldTimer(int worldId)
    {
        WorldTimer timer = worldTimers.get(worldId);

        // If the current world is not in the tracked list (e.g. F2P or untracked member world)
        // and we are requesting the timer for the local player's world, provide a default 'No Data' timer.
        if (timer == null && client.getWorld() == worldId)
        {
            return new WorldTimer(worldId, Instant.MIN);
        }

        return timer;
    }

    /**
     * Returns a list of all world timers.
     */
    public List<WorldTimer> getAllWorldTimers()
    {
        return new ArrayList<>(worldTimers.values());
    }

    public com.osrspluginz.maledictus.MaledictusConfig getConfig() { return config; }
    public Client getClient() { return client; }
    public BufferedImage getSkullWhite() { return skullWhite; }
    public BufferedImage getSkullRed() { return skullRed; }
    public BufferedImage getSkullPanel() { return skullPanel; }

    public void setOverlayConfig(boolean selected)
    {
        configManager.setConfiguration(
                com.osrspluginz.maledictus.MaledictusConfig.class.getAnnotation(ConfigGroup.class).value(),
                "showOverlay",
                selected
        );
    }

    // --- WORLD HOPPING LOGIC (Merged for thread safety) ---

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // Failsafe: If worlds failed to load at startup (null), try loading them when game state changes (e.g. login)
        if (worldTimers.isEmpty() && client.getGameState().getState() >= GameState.LOGIN_SCREEN.getState())
        {
            loadWorldList();
        }
    }

    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        handleHop();
    }

    // Public method called by the MaledictusTimerRow (Swing Thread)
    public void hopTo(int worldId)
    {
        // All checks and the hop execution must run on the Client Thread
        clientThread.invoke(() -> {

            if (client.getWorld() == worldId)
            {
                sendConsoleMessage("You are already on World " + worldId);
                return;
            }

            if (client.getGameState() != GameState.LOGGED_IN && client.getGameState() != GameState.LOGIN_SCREEN)
            {
                sendConsoleMessage("Cannot quick-hop while not logged in or at login screen.");
                return;
            }

            // Check 1: Config is enabled
            if (!config.isWorldHopperEnabled())
            {
                sendConsoleMessage("World hopping is disabled in the plugin configuration.");
                return;
            }

            WorldResult worldResult = worldService.getWorlds();
            // Check 2: World list is successfully fetched
            if (worldResult == null)
            {
                sendConsoleMessage("Failed to fetch world list from RuneLite API. Cannot hop.");
                return;
            }

            World world = worldResult.findWorld(worldId);
            // Check 3: Target world exists in the list
            if (world == null)
            {
                sendConsoleMessage("World ID " + worldId + " not found in the fetched world list. Cannot hop.");
                return;
            }

            final net.runelite.api.World rsWorld = client.createWorld();
            rsWorld.setActivity(world.getActivity());
            rsWorld.setAddress(world.getAddress());
            rsWorld.setId(world.getId());
            rsWorld.setPlayerCount(world.getPlayers());
            rsWorld.setLocation(world.getLocation());
            rsWorld.setTypes(WorldUtil.toWorldTypes(world.getTypes()));

            sendConsoleMessage("Quick-hopping to World " + world.getId() + "...");

            if (client.getGameState() == GameState.LOGIN_SCREEN)
            {
                client.changeWorld(rsWorld);
                return;
            }

            // Only set the target world if all checks pass
            quickHopTargetWorld = rsWorld;
            displaySwitcherAttempts = 0;
        });
    }

    private void handleHop()
    {
        if (quickHopTargetWorld == null)
        {
            return;
        }

        if (client.getWidget(ComponentID.WORLD_SWITCHER_WORLD_LIST) == null)
        {
            client.openWorldHopper();

            if (++displaySwitcherAttempts >= DISPLAY_SWITCHER_MAX_ATTEMPTS)
            {
                sendConsoleMessage("Failed to quick-hop after " + displaySwitcherAttempts + " attempts. Aborting hop target. (Game likely blocking the hop)");

                resetQuickHopper();
            }
        }
        else
        {
            // World switcher is open, execute the hop
            client.hopToWorld(quickHopTargetWorld);
            resetQuickHopper();
        }
    }

    private void resetQuickHopper()
    {
        quickHopTargetWorld = null;
        displaySwitcherAttempts = 0;
    }
}