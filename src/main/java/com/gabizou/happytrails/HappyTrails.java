/*
 * This file is part of HappyTrails, licensed under the MIT License (MIT).
 *
 * Copyright (c) Gabriel Harris-Rouquette <https://gabizou.com/>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.gabizou.happytrails;

import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.slf4j.Logger;
import org.spongepowered.api.GameRegistry;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.data.DataManager;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.DataRegistration;
import org.spongepowered.api.data.key.Key;
import org.spongepowered.api.data.value.mutable.Value;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.Getter;
import org.spongepowered.api.event.game.GameRegistryEvent;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.*;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.Task;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Plugin(
    id = Constants.MOD_ID,
    name = Constants.MOD_NAME,
    description = "A fancy plugin making trails for players to use",
    authors = {
        "gabizou"
    },
    url = "https://gabizou.com/"
)
public class HappyTrails {

    private static final TypeToken<Value<Trail>> TRAIL_VALUE_TOKEN = new TypeToken<Value<Trail>>() {};
    static final Key<Value<Trail>>
        TRAIL = Key.builder().type(TRAIL_VALUE_TOKEN).query(Constants.KEY_QUERY).id(Constants.KEY_ID).name("Trail").build();

    @Nullable private static HappyTrails /* this is really hacky*/ INSTANCE;

    Logger logger;
    private GameRegistry registry;
    private DataManager manager;
    private PluginContainer container;

    private Path defaultConfig;

    private HoconConfigurationLoader loader;

    private Map<PlayerWrapper, Trail> playerTrails = new HashMap<>();

    @Nullable private Task particleTask;
    private TrailConfig config;

    @Inject
    private HappyTrails(
            Logger logger,
            GameRegistry registry,
            PluginContainer container,
            @DefaultConfig(sharedRoot = false) Path defaultConfig,
            DataManager dataManager) {
        this.logger = logger;
        this.registry = registry;
        this.manager = dataManager;
        this.container = container;
        this.defaultConfig = defaultConfig;
        this.loader = HoconConfigurationLoader.builder().setPath(this.defaultConfig).build();
        this.config = new TrailConfig();
        INSTANCE = this;
    }


    static HappyTrails getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("HappyTrails has not loaded yet!");
        }
        return INSTANCE;
    }

    @Listener
    public void onGameStart(GamePreInitializationEvent event) {
        this.registry.registerModule(Trail.class, TrailRegistry.getInstance());

    }

    @Listener
    public void registerModule(GameRegistryEvent.Register<DataRegistration<?, ?>> event) {
        this.manager.registerBuilder(Trail.class, new Trail.Builder());
        DataRegistration.builder()
            .dataClass(TrailData.class)
            .immutableClass(TrailData.Immutable.class)
            .builder(new TrailData.Builder())
            .manipulatorId(Constants.KEY_ID)
            .dataName("Trail Data")
            .buildAndRegister(this.container);
    }

    @Listener
    public void registerKeys(GameRegistryEvent.Register<Key<?>> event) {
        event.register(HappyTrails.TRAIL);
    }

    @Listener
    public void onGameInit(GameInitializationEvent event) {
        //Load up config files for trail registrations
        if (!Files.exists(this.defaultConfig)) {
            try {
                populateTrailsFromConfig();
            } catch (IOException | ObjectMappingException e) {
                e.printStackTrace();
            }
        }
        loadConfig();
    }

    @Listener
    public void onReload(GameReloadEvent event) {
        if (!Files.exists(this.defaultConfig)) {
            try {
                populateTrailsFromConfig();
            } catch (IOException | ObjectMappingException e) {
                e.printStackTrace();
            }
        }
        loadConfig();
    }

    private void loadConfig() {
        try {
            final CommentedConfigurationNode config = this.loader.load();
            final TrailConfig trailConfig = config.getValue(TrailConfig.TYPE_TOKEN);
            this.config = trailConfig == null ? new TrailConfig() : trailConfig;
        } catch (IOException | ObjectMappingException e) {
            e.printStackTrace();
        } finally {
            TrailRegistry.getInstance().registerFromConfig(this.config);
        }
    }

    private void populateTrailsFromConfig() throws IOException, ObjectMappingException {
        Files.createFile(this.defaultConfig);
        final TrailConfig newConfig = new TrailConfig();
        TrailRegistry.getInstance().registerFromConfig(newConfig);
        final ConfigurationNode configurationNode = this.loader.createEmptyNode().setValue(TrailConfig.TYPE_TOKEN, newConfig);
        this.loader.save(configurationNode);
    }

    @Listener
    public void onGamePostInit(GamePostInitializationEvent event) {
        Sponge.getCommandManager().register(this, TrailCommands.getCommand(), "trail", "happytrails", "trails");
    }

    @Listener(order = Order.POST)
    public void onServerStart(GameStartedServerEvent event) {
        this.particleTask = Task.builder()
            .intervalTicks(1)
            .name("Particle Spawner")
            .execute(() -> {
                if (this.playerTrails.isEmpty()) {
                    return;
                }
                final Iterator<Map.Entry<PlayerWrapper, Trail>> iterator = this.playerTrails.entrySet().iterator();
                for (; iterator.hasNext(); ) {
                    Map.Entry<PlayerWrapper, Trail> entry = iterator.next();
                    final Trail value = entry.getValue();
                    final PlayerWrapper playerId = entry.getKey();
                    final Player player;
                    try {
                        player = playerId.getPlayer();
                    } catch (Exception e) {
                        iterator.remove();
                        continue;
                    }
                    if (playerId.cooldown > 0) {
                        playerId.cooldown--;
                        continue;
                    }
                    playerId.cooldown = value.period;
                    value.playEffect(player);

                }
            })
            .submit(this);
    }

    @Listener
    public void onServerStop(GameStoppingServerEvent event) {
        if (this.particleTask != null) {
            this.particleTask.cancel();
            this.particleTask = null;
        }
        this.playerTrails.clear();
    }


    @Listener
    public void onPlayerJoin(ClientConnectionEvent.Join event, @Getter("getTargetEntity") Player player) {
        player.get(TrailData.class).ifPresent(data -> this.playerTrails.put(new PlayerWrapper(player), data.getTrail()));
    }


    @Listener
    public void onDisconnect(ClientConnectionEvent.Disconnect event, @Getter("getTargetEntity") Player player) {
        // Remove the player from the mapping, other reasons why the player would be removed
        // are unknown.
        for (final Iterator<Map.Entry<PlayerWrapper, Trail>> iterator = this.playerTrails.entrySet().iterator(); iterator.hasNext(); ) {
            final Map.Entry<PlayerWrapper, Trail> next = iterator.next();
            if (next.getKey().playerId.equals(player.getUniqueId())) {
                iterator.remove();
                break;
            }
        }

    }

    void setPlayer(Player player, Trail trail) {
        boolean existed = false;
        for (final Map.Entry<PlayerWrapper, Trail> next : this.playerTrails.entrySet()) {
            if (next.getKey().playerId.equals(player.getUniqueId())) {
                next.setValue(trail);
                existed = true;
                break;
            }
        }
        if (!existed) {
            this.playerTrails.put(new PlayerWrapper(player), trail);
        }
        final TrailData trailData = player.get(TrailData.class).orElseGet(() -> new TrailData(trail));
        trailData.setTrail(trail);
        player.offer(trailData);
    }

    void removePlayer(Player player) {
        final Iterator<Map.Entry<PlayerWrapper, Trail>> iterator = this.playerTrails.entrySet().iterator();
        for (; iterator.hasNext(); ) {
            final Map.Entry<PlayerWrapper, Trail> next = iterator.next();
            if (next.getKey().playerId.equals(player.getUniqueId())) {
                iterator.remove();
                break;
            }
        }
        player.remove(TrailData.class);

    }
}
