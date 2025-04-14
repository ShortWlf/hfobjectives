package com.hfobjectives;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Hfobjectives implements ModInitializer {
	public static final String MOD_ID = "hfobjectives";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Configuration file path.
	private static final String CONFIG_PATH = "config/hfobjectives.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Loaded objectives and text settings.
	private static final List<CustomObjective> objectivesConfig = new ArrayList<>();
	private static TextSettings textSettings;

	// Active objectives, keyed by player's UUID.
	private static final ConcurrentHashMap<UUID, ActiveObjective> activeObjectives = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing HF Objectives mod!");
		loadConfig();

		// Register commands.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// /startobjective command.
			dispatcher.register(CommandManager.literal("startobjective")
					.then(CommandManager.argument("objectiveId", StringArgumentType.word())
							.executes(context -> {
								ServerCommandSource source = context.getSource();
								ServerPlayerEntity player;
								try {
									player = source.getPlayer();
								} catch (Exception e) {
									source.sendFeedback(() -> Text.literal("This command can only be used by a player."), true);
									return 1;
								}
								String objectiveId = StringArgumentType.getString(context, "objectiveId");
								CustomObjective objective = getObjectiveById(objectiveId);
								if (objective == null) {
									source.sendFeedback(() -> Text.literal("Objective with ID '" + objectiveId + "' not found."), true);
									return 1;
								}
								if (activeObjectives.containsKey(player.getUuid())) {
									source.sendFeedback(() -> Text.literal("You already have an active objective!"), true);
									return 1;
								}

								String messageText = "Objective Started: " + objective.description();
								player.sendMessage(Text.literal(messageText)
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
								source.sendFeedback(() -> Text.literal(messageText)
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);

								ActiveObjective active = new ActiveObjective(objective, objective.timeLimit());
								active.initialHealth = player.getHealth();
								if ("mobkill".equalsIgnoreCase(objective.objectiveType())) {
									active.killCount = 0;
								}
								activeObjectives.put(player.getUuid(), active);
								return 1;
							})
					)
			);

			// /completeobjective command.
			dispatcher.register(CommandManager.literal("completeobjective")
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						ServerPlayerEntity player;
						try {
							player = source.getPlayer();
						} catch (Exception e) {
							source.sendFeedback(() -> Text.literal("This command can only be used by a player."), true);
							return 1;
						}
						ActiveObjective active = activeObjectives.get(player.getUuid());
						if (active == null) {
							source.sendFeedback(() -> Text.literal("You don't have any active objective.")
									.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
							return 1;
						}
						active.completed = true;
						activeObjectives.remove(player.getUuid());
						source.sendFeedback(() -> Text.literal("Objective Complete!")
								.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
						return 1;
					})
			);

			// /randobj command.
			dispatcher.register(CommandManager.literal("randobj")
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						ServerPlayerEntity player;
						try {
							player = source.getPlayer();
						} catch (Exception e) {
							source.sendFeedback(() -> Text.literal("This command can only be used by a player."), true);
							return 1;
						}
						if (activeObjectives.containsKey(player.getUuid())) {
							source.sendFeedback(() -> Text.literal("You already have an active objective!"), true);
							return 1;
						}
						if (objectivesConfig.isEmpty()) {
							source.sendFeedback(() -> Text.literal("No objectives available!"), true);
							return 1;
						}
						Random rand = new Random();
						CustomObjective objective = objectivesConfig.get(rand.nextInt(objectivesConfig.size()));
						String messageText = "Objective Started: " + objective.description();
						player.sendMessage(Text.literal(messageText)
								.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
						source.sendFeedback(() -> Text.literal(messageText)
								.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
						ActiveObjective active = new ActiveObjective(objective, objective.timeLimit());
						active.initialHealth = player.getHealth();
						if ("mobkill".equalsIgnoreCase(objective.objectiveType())) {
							active.killCount = 0;
						}
						activeObjectives.put(player.getUuid(), active);
						return 1;
					})
			);

			// /hfreloadobjectives command.
			dispatcher.register(CommandManager.literal("hfreloadobjectives")
					.requires(src -> src.hasPermissionLevel(2))
					.executes(context -> {
						loadConfig();
						context.getSource().sendFeedback(() -> Text.literal("Objectives configuration reloaded.")
								.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
						return 1;
					})
			);
		});

		// AFTER_KILLED event: for direct kills.
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, killedEntity, killer) -> {
			if (killer instanceof ServerPlayerEntity) {
				ServerPlayerEntity player = (ServerPlayerEntity) killer;
				ActiveObjective active = activeObjectives.get(player.getUuid());
				if (active != null && "mobkill".equalsIgnoreCase(active.objective.objectiveType())) {
					String target = active.objective.target().trim().toLowerCase();
					Identifier killedEntityId = Registries.ENTITY_TYPE.getId(killedEntity.getType());
					if (killedEntityId != null) {
						String idStr = killedEntityId.toString().trim().toLowerCase();
						LOGGER.info("AFTER_KILLED event: target=[{}], killedEntityId=[{}]", target, idStr);
						if (target.equals(idStr)) {
							active.killCount++;
							LOGGER.info("Incremented kill count (event): now {}", active.killCount);
						}
					}
				}
			}
		});

		// TICK event: update on-screen status and monitor objectives.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.currentTimeMillis();
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				ActiveObjective active = activeObjectives.get(player.getUuid());
				if (active != null && !active.completed) {
					// For dontgethit objectives, fail immediately if damage is detected.
					if ("dontgethit".equalsIgnoreCase(active.objective.objectiveType()) &&
							player.getHealth() < active.initialHealth) {
						active.completed = true;
						activeObjectives.remove(player.getUuid());
						killPlayer(player, server.getCommandSource());
						player.sendMessage(Text.literal("Objective Failed: You took damage!")
								.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
						continue;
					}

					// For mobkill objectives, monitor nearby mobs as a backup.
					if ("mobkill".equalsIgnoreCase(active.objective.objectiveType())) {
						double radius = 10.0D;
						ServerWorld serverWorld = (ServerWorld) player.getWorld();
						Box box = new Box(player.getX() - radius, player.getY() - radius, player.getZ() - radius,
								player.getX() + radius, player.getY() + radius, player.getZ() + radius);
						List<Entity> nearbyEntities = serverWorld.getEntitiesByClass(Entity.class, box,
								entity -> !(entity instanceof ServerPlayerEntity));
						for (Entity entity : nearbyEntities) {
							Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
							if (id != null) {
								String idStr = id.toString().trim().toLowerCase();
								if (active.objective.target().trim().toLowerCase().equals(idStr)) {
									active.monitoredEntities.add(entity.getUuid());
								}
							}
						}
						// Check for monitored entities that no longer exist.
						List<UUID> monitoredCopy = new ArrayList<>(active.monitoredEntities);
						for (UUID uuid : monitoredCopy) {
							if (serverWorld.getEntity(uuid) == null) {
								active.killCount++;
								active.monitoredEntities.remove(uuid);
								LOGGER.info("Incremented kill count (monitoring): now {}", active.killCount);
							}
						}
					}

					int remainingSeconds = (int) Math.ceil((active.endTime - now) / 1000.0);
					if (remainingSeconds < 0) remainingSeconds = 0;

					// Build an on-screen status message.
					MutableText message = Text.literal("");
					String type = active.objective.objectiveType().toLowerCase();
					if ("gather".equals(type)) {
						int currentCount = countItemInInventory(player, active.objective.target());
						message = message.append(Text.literal("Objective: " + active.objective.description() + " | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Counter: " + currentCount + "/" + active.objective.amount() + " | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Time: " + remainingSeconds + "s")
										.setStyle(Style.EMPTY.withColor(getTimerColor())));
						if (currentCount >= active.objective.amount()) {
							active.completed = true;
							activeObjectives.remove(player.getUuid());
							message = Text.literal("Objective Complete!")
									.setStyle(Style.EMPTY.withColor(getObjectiveColor()));
						}
					} else if ("mobkill".equals(type)) {
						int currentKills = active.killCount;
						message = message.append(Text.literal("Objective: " + active.objective.description() + " | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Kills: " + currentKills + "/" + active.objective.amount() + " | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Time: " + remainingSeconds + "s")
										.setStyle(Style.EMPTY.withColor(getTimerColor())));
						if (currentKills >= active.objective.amount()) {
							active.completed = true;
							activeObjectives.remove(player.getUuid());
							message = Text.literal("Objective Complete!")
									.setStyle(Style.EMPTY.withColor(getObjectiveColor()));
						}
					} else if ("dontgethit".equals(type)) {
						message = message.append(Text.literal("Objective: " + active.objective.description() + " | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Time: " + remainingSeconds + "s")
										.setStyle(Style.EMPTY.withColor(getTimerColor())));
						if (remainingSeconds <= 0 && !active.completed) {
							active.completed = true;
							activeObjectives.remove(player.getUuid());
							message = Text.literal("Objective Complete!")
									.setStyle(Style.EMPTY.withColor(getObjectiveColor()));
						}
					} else if ("stand".equals(type)) { // New "stand" objective type.
						ServerWorld serverWorld = (ServerWorld) player.getWorld();
						BlockPos pos = player.getBlockPos().down(); // Block directly under the player.
						Identifier currentBlockId = Registries.BLOCK.getId(serverWorld.getBlockState(pos).getBlock());
						if (currentBlockId != null && currentBlockId.toString().trim().equalsIgnoreCase(active.objective.target().trim())) {
							active.standingTicks++;  // Increment tick count if standing on the correct block.
						} else {
							active.standingTicks = 0;  // Reset if player steps off.
						}
						int secondsStanding = active.standingTicks / 20; // Convert ticks to seconds.
						message = message.append(Text.literal("Objective: " + active.objective.description() + " | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Standing: " + secondsStanding + "/" + active.objective.amount() + " sec | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Time: " + remainingSeconds + "s")
										.setStyle(Style.EMPTY.withColor(getTimerColor())));
						if (secondsStanding >= active.objective.amount()) {
							active.completed = true;
							activeObjectives.remove(player.getUuid());
							message = Text.literal("Objective Complete!")
									.setStyle(Style.EMPTY.withColor(getObjectiveColor()));
						}
					} else {
						message = message.append(Text.literal("Objective: " + active.objective.description() + " | ")
										.setStyle(Style.EMPTY.withColor(getObjectiveColor())))
								.append(Text.literal("Time: " + remainingSeconds + "s")
										.setStyle(Style.EMPTY.withColor(getTimerColor())));
					}
					// Display the unified status message on the action bar.
					player.sendMessage(message, true);
					if (!"dontgethit".equals(type) && remainingSeconds <= 0 && !active.completed) {
						active.completed = true;
						activeObjectives.remove(player.getUuid());
						killPlayer(player, server.getCommandSource());
						player.sendMessage(Text.literal("Objective Failed!")
								.setStyle(Style.EMPTY.withColor(getObjectiveColor())), true);
					}
				}
			}
		});
	}

	/**
	 * Helper method to execute the kill command on the given player.
	 */
	private void killPlayer(ServerPlayerEntity player, ServerCommandSource source) {
		try {
			source.getServer().getCommandManager().getDispatcher().execute(
					"kill @p[name=" + player.getGameProfile().getName() + "]", source);
		} catch (CommandSyntaxException e) {
			LOGGER.error("Error executing kill command: ", e);
		}
	}

	/**
	 * Helper method to count a specified item in a player's inventory.
	 */
	private int countItemInInventory(ServerPlayerEntity player, String itemIdString) {
		int count = 0;
		Identifier identifier = Identifier.tryParse(itemIdString);
		if (identifier == null) return 0;
		Item targetItem = Registries.ITEM.get(identifier);
		for (ItemStack stack : player.getInventory()) {
			if (stack.getItem() == targetItem) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/**
	 * Loads the mod's configuration from the file.
	 */
	private static void loadConfig() {
		File configFile = new File(CONFIG_PATH);
		if (!configFile.exists()) {
			LOGGER.info("Config not found. Creating default config.");
			createDefaultConfig(configFile);
		}
		try (FileReader reader = new FileReader(configFile)) {
			Type configType = new TypeToken<ModConfig>() {}.getType();
			ModConfig modConfig = GSON.fromJson(reader, configType);
			objectivesConfig.clear();
			if (modConfig.objectives() != null) {
				objectivesConfig.addAll(modConfig.objectives());
			}
			textSettings = modConfig.textSettings();
			LOGGER.info("Loaded {} objectives, text settings: objectiveColor={}, timerColor={}",
					objectivesConfig.size(), textSettings.objectiveColor(), textSettings.timerColor());
		} catch (IOException e) {
			LOGGER.error("Error loading config", e);
		}
	}

	/**
	 * Creates the default configuration file.
	 */
	private static void createDefaultConfig(File configFile) {
		try {
			File parent = configFile.getParentFile();
			if (!parent.exists() && !parent.mkdirs()) {
				LOGGER.error("Failed to create config directory!");
				return;
			}
			List<CustomObjective> defaults = new ArrayList<>();
			defaults.add(new CustomObjective("gatherdirt", "Gather dirt", 30, "gather", "minecraft:dirt", 1));
			defaults.add(new CustomObjective("gatherstone", "Collect stone", 45, "gather", "minecraft:stone", 5));
			defaults.add(new CustomObjective("collectarrows", "Collect at least 10 arrows", 30, "gather", "minecraft:arrow", 10));
			defaults.add(new CustomObjective("mobkillzombie", "Eliminate 3 zombies", 60, "mobkill", "minecraft:zombie", 3));
			defaults.add(new CustomObjective("dontgethitchallenge", "Survive without damage", 60, "dontgethit", "", 0));
			// New "stand" objective: stand on a gold block for 5 seconds.
			defaults.add(new CustomObjective("standongold", "Stand on a gold block for 5 seconds", 30, "stand", "minecraft:gold_block", 5));

			TextSettings defaultText = new TextSettings("green", "red");
			ModConfig defaultConfig = new ModConfig(defaultText, defaults);

			try (FileWriter writer = new FileWriter(configFile)) {
				GSON.toJson(defaultConfig, writer);
				LOGGER.info("Default config created at {}", CONFIG_PATH);
			}
		} catch (IOException e) {
			LOGGER.error("Error creating default config", e);
		}
	}

	/**
	 * Retrieves an objective by its ID.
	 */
	private static CustomObjective getObjectiveById(String id) {
		for (CustomObjective obj : objectivesConfig) {
			if (obj.id().equalsIgnoreCase(id)) {
				return obj;
			}
		}
		return null;
	}

	/**
	 * Returns the configured objective color as a Formatting value.
	 */
	private static Formatting getObjectiveColor() {
		try {
			return Formatting.valueOf(textSettings.objectiveColor().toUpperCase());
		} catch (IllegalArgumentException e) {
			return Formatting.GREEN;
		}
	}

	/**
	 * Returns the configured timer color as a Formatting value.
	 */
	private static Formatting getTimerColor() {
		try {
			return Formatting.valueOf(textSettings.timerColor().toUpperCase());
		} catch (IllegalArgumentException e) {
			return Formatting.RED;
		}
	}

	// --- Configuration Records ---

	/**
	 * The mod configuration record which contains text settings and objectives.
	 */
	public record ModConfig(TextSettings textSettings, List<CustomObjective> objectives) { }

	/**
	 * Text settings for the mod.
	 * objectiveColor: used for objective messages (default bright green).
	 * timerColor: used for timer messages (default red).
	 */
	public record TextSettings(String objectiveColor, String timerColor) { }

	/**
	 * Record representing a custom objective.
	 */
	public record CustomObjective(
			String id,
			String description,
			int timeLimit,
			String objectiveType,  // "gather", "manual", "mobkill", "dontgethit", "stand"
			String target,         // e.g. "minecraft:dirt", "minecraft:zombie", "minecraft:gold_block" for 'stand'
			int amount             // required count for 'gather'/'mobkill' or required seconds for 'stand'
	) { }

	/**
	 * Holds state for an active objective assigned to a player.
	 * For mobkill objectives, monitoredEntities tracks nearby entities by UUID.
	 */
	private static class ActiveObjective {
		public final CustomObjective objective;
		public boolean completed;
		public final long startTime;
		public final long endTime;
		public float initialHealth;
		public int killCount;
		public int standingTicks; // Tracks tick count for "stand" objectives.
		public final HashSet<UUID> monitoredEntities = new HashSet<>();

		public ActiveObjective(CustomObjective objective, int timeLimit) {
			this.objective = objective;
			this.completed = false;
			this.startTime = System.currentTimeMillis();
			this.endTime = this.startTime + timeLimit * 1000L;
			this.initialHealth = 0.0F;
			this.killCount = 0;
			this.standingTicks = 0;
		}
	}
}
