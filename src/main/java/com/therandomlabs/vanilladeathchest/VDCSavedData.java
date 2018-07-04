package com.therandomlabs.vanilladeathchest;

import java.util.ArrayList;
import java.util.List;
import com.therandomlabs.vanilladeathchest.base.VanillaDeathChest;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

public class VDCSavedData extends WorldSavedData {
	public static final String DATA_NAME = VanillaDeathChest.MODID + "_data";
	public static final String TAG_KEY = "DeathChests";

	private List<BlockPos> deathChests = new ArrayList<>();

	public VDCSavedData() {
		super(DATA_NAME);
	}

	public VDCSavedData(String name) {
		super(name);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		deathChests.clear();

		final NBTTagList list = nbt.getTagList(TAG_KEY, Constants.NBT.TAG_COMPOUND);

		for(NBTBase tag : list) {
			deathChests.add(NBTUtil.getPosFromTag((NBTTagCompound) tag));
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		final NBTTagList tagList = new NBTTagList();

		for(BlockPos pos : deathChests) {
			tagList.appendTag(NBTUtil.createPosTag(pos));
		}

		nbt.setTag(TAG_KEY, tagList);
		return nbt;
	}

	public List<BlockPos> getDeathChests() {
		return deathChests;
	}

	public static VDCSavedData get(World world) {
		final MapStorage storage = world.getMapStorage();
		VDCSavedData instance = (VDCSavedData) storage.getOrLoadData(VDCSavedData.class, DATA_NAME);

		if(instance == null) {
			instance = new VDCSavedData();
			storage.setData(DATA_NAME, instance);
		}

		return instance;
	}
}
