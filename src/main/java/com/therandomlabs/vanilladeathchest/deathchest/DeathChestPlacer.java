/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021 TheRandomLabs
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.therandomlabs.vanilladeathchest.deathchest;

import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.therandomlabs.vanilladeathchest.VDCConfig;
import com.therandomlabs.vanilladeathchest.VanillaDeathChest;
import com.therandomlabs.vanilladeathchest.util.DeathChestDefenseEntity;
import com.therandomlabs.vanilladeathchest.world.DeathChestsState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

/**
 * Handles death chest placement.
 */
public final class DeathChestPlacer {
	private enum ContainerConsumptionResult {
		FAILED,
		SINGLE,
		DOUBLE
	}

	private DeathChestPlacer() {}

	/**
	 * Places all queued death chests that are ready to be placed in the specified world.
	 * This is called at the end of every world tick.
	 *
	 * @param world a {@link ServerWorld}.
	 */
	@SuppressWarnings("ConstantConditions")
	public static void placeQueued(ServerWorld world) {
		final DeathChestsState state = DeathChestsState.get(world);
		final Queue<DeathChest> queue = state.getQueuedDeathChests();

		//We wait two ticks to prevent conflicts with other mods that place things after death.
		if (queue.isEmpty() || world.getTime() - queue.peek().getCreationTime() < 2L) {
			return;
		}

		while (!queue.isEmpty() && world.getTime() - queue.peek().getCreationTime() >= 2L) {
			placeAndDropRemaining(queue.poll());
		}

		state.markDirty();
	}

	private static void placeAndDropRemaining(DeathChest deathChest) {
		final List<ItemEntity> allItems = deathChest.cloneItems();
		place(allItems, deathChest);

		final World world = deathChest.getWorld();

		for (ItemEntity drop : allItems) {
			if (!deathChest.getItems().contains(drop)) {
				world.spawnEntity(new ItemEntity(
						world, drop.getX(), drop.getY(), drop.getZ(), drop.getStack()
				));
			}
		}
	}

	@SuppressWarnings("ConstantConditions")
	private static void place(List<ItemEntity> allItems, DeathChest deathChest) {
		final VDCConfig.Spawning config = VanillaDeathChest.config().spawning;
		final List<ItemEntity> items = deathChest.getItems();

		final Pattern pattern = Pattern.compile(config.registryNameRegex);
		items.removeIf(
				item -> !pattern.matcher(
						Registry.ITEM.getId(item.getStack().getItem()).toString()
				).matches()
		);

		final VDCConfig.ContainerType type = config.containerType;
		boolean doubleChest = items.size() > 27 &&
				(type == VDCConfig.ContainerType.SINGLE_OR_DOUBLE_CHEST ||
						type == VDCConfig.ContainerType.SINGLE_OR_DOUBLE_SHULKER_BOX);

		if (config.useContainerInInventory) {
			final ContainerConsumptionResult result =
					consumeContainerInInventory(allItems, deathChest, doubleChest);

			if (result == ContainerConsumptionResult.FAILED) {
				return;
			}

			if (result == ContainerConsumptionResult.SINGLE) {
				doubleChest = false;
			}
		}

		final DeathChestLocationFinder.Location location =
				DeathChestLocationFinder.find(deathChest);

		if (location == null) {
			VanillaDeathChest.logger.warn(
					"No death chest location found for player at [{}]", deathChest.getPos()
			);
			return;
		}

		if (!location.isDoubleChest()) {
			doubleChest = false;
		}

		final BlockPos pos = location.getPos();
		final ServerWorld world = deathChest.getWorld();
		final DeathChest newDeathChest = new DeathChest(
				world, deathChest.getPlayerUUID(), items, deathChest.getInventory(),
				world.getTime(), pos, doubleChest, true
		);

		if (!placeAndFillContainer(newDeathChest)) {
			return;
		}

		final PlayerEntity player = world.getPlayerByUuid(deathChest.getPlayerUUID());

		spawnDefenseEntities(deathChest, player);

		DeathChestsState.get(world).addDeathChest(newDeathChest);

		VanillaDeathChest.logger.info(
				"Death chest for {} spawned at [{}]", player.getGameProfile().getName(), pos
		);

		player.sendMessage(new LiteralText(String.format(
				config.spawnMessage, pos.getX(), pos.getY(), pos.getZ()
		)), false);
	}

	private static ContainerConsumptionResult consumeContainerInInventory(
			List<ItemEntity> allItems, DeathChest deathChest, boolean doubleChest
	) {
		final VDCConfig.ContainerType type = VanillaDeathChest.config().spawning.containerType;
		final Set<ItemEntity> emptyItems = new HashSet<>();

		int availableContainers = 0;

		for (ItemEntity item : deathChest.getItems()) {
			final ItemStack stack = item.getStack();

			if (type == VDCConfig.ContainerType.SINGLE_CHEST ||
					type == VDCConfig.ContainerType.SINGLE_OR_DOUBLE_CHEST) {
				if (stack.getItem() != Item.BLOCK_ITEMS.get(Blocks.CHEST)) {
					continue;
				}
			} else {
				if (!(Block.getBlockFromItem(stack.getItem()) instanceof ShulkerBoxBlock)) {
					continue;
				}

				final CompoundTag tag = stack.getTag();

				if (tag != null) {
					final DefaultedList<ItemStack> inventory =
							DefaultedList.ofSize(27, ItemStack.EMPTY);
					Inventories.fromTag(tag.getCompound("BlockEntityTag"), inventory);

					//The shulker box must be empty.
					if (inventory.stream().anyMatch(itemStack -> !itemStack.isEmpty())) {
						continue;
					}
				}
			}

			if (!doubleChest || stack.getCount() > 1) {
				availableContainers = doubleChest ? 2 : 1;
				stack.decrement(availableContainers);

				if (stack.isEmpty()) {
					emptyItems.add(item);
				}

				break;
			}

			//doubleChest is true, but stack.getCount() is only 1.
			availableContainers++;
			stack.decrement(1);

			if (stack.isEmpty()) {
				emptyItems.add(item);
			}

			if (availableContainers == 2) {
				break;
			}
		}

		if (availableContainers == 0) {
			return ContainerConsumptionResult.FAILED;
		}

		//Remove them from allItems so that placeAndDropRemaining doesn't try to drop them.
		allItems.removeAll(emptyItems);
		deathChest.getItems().removeAll(emptyItems);

		return availableContainers == 1 ?
				ContainerConsumptionResult.SINGLE : ContainerConsumptionResult.DOUBLE;
	}

	@SuppressWarnings("NullAway")
	private static boolean placeAndFillContainer(DeathChest deathChest) {
		final VDCConfig.Spawning config = VanillaDeathChest.config().spawning;
		final BlockPos pos = deathChest.getPos();
		final List<ItemEntity> items = deathChest.getItems();
		final boolean doubleChest = deathChest.isDoubleChest();

		final Block block;

		if (config.containerType == VDCConfig.ContainerType.SINGLE_SHULKER_BOX ||
				config.containerType == VDCConfig.ContainerType.SINGLE_OR_DOUBLE_SHULKER_BOX) {
			block = ShulkerBoxBlock.get(config.shulkerBoxColor.get());
		} else {
			block = Blocks.CHEST;
		}

		final BlockPos east = pos.east();
		final World world = deathChest.getWorld();
		final BlockState state = block.getDefaultState();

		if (doubleChest) {
			if (block == Blocks.CHEST) {
				world.setBlockState(pos, state.with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
				world.setBlockState(east, state.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
			} else {
				world.setBlockState(pos, state);
				world.setBlockState(east, state);
			}
		} else {
			world.setBlockState(pos, state);
		}

		final BlockEntity blockEntity = world.getBlockEntity(pos);
		final BlockEntity eastBlockEntity = doubleChest ? world.getBlockEntity(east) : null;

		if (!(blockEntity instanceof LootableContainerBlockEntity) ||
				(doubleChest && !(eastBlockEntity instanceof LootableContainerBlockEntity))) {
			VanillaDeathChest.logger.warn(
					"Failed to place death chest at [{}] due to invalid block entity", pos
			);
			return false;
		}

		final LootableContainerBlockEntity container =
				(LootableContainerBlockEntity) (doubleChest ? eastBlockEntity : blockEntity);

		for (int i = 0; i < 27; i++) {
			container.setStack(i, items.get(i).getStack());
		}

		if (!config.containerDisplayName.isEmpty()) {
			container.setCustomName(new LiteralText(config.containerDisplayName));
		}

		if (doubleChest) {
			for (int i = 27; i < items.size(); i++) {
				container.setStack(i - 27, items.get(i).getStack());
			}

			if (!config.containerDisplayName.isEmpty()) {
				container.setCustomName(new LiteralText(config.containerDisplayName));
			}
		} else if (items.size() > 27) {
			items.subList(27, items.size()).clear();
		}

		return true;
	}

	private static void spawnDefenseEntities(DeathChest deathChest, PlayerEntity player) {
		final VDCConfig.DefenseEntities config = VanillaDeathChest.config().defenseEntities;

		if (config.entityType == null) {
			return;
		}

		final ServerWorld world = deathChest.getWorld();
		final BlockPos pos = deathChest.getPos();
		final double x = pos.getX() + 0.5;
		final double y = pos.getY() + 1.0;
		final double z = pos.getZ() + 0.5;

		for (int i = 0; i < config.spawnCount; i++) {
			CompoundTag tag;

			try {
				tag = StringNbtReader.parse(config.nbtTag);
			} catch (CommandSyntaxException ignored) {
				//This should not happen.
				tag = new CompoundTag();
			}

			tag.putString("id", config.registryName);

			final Entity entity = EntityType.loadEntityWithPassengers(
					tag, world, spawnedEntity -> {
						spawnedEntity.setPos(x, y, z);
						return !world.tryLoadEntity(spawnedEntity) ? null : spawnedEntity;
					}
			);

			if (entity instanceof DeathChestDefenseEntity) {
				((DeathChestDefenseEntity) entity).setDeathChest(deathChest);
			}
		}
	}
}
