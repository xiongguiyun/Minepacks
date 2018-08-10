/*
 *   Copyright (C) 2016-2018 GeorgH93
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package at.pcgamingfreaks.Minepacks.Bukkit.Database;

import at.pcgamingfreaks.ConsoleColor;
import at.pcgamingfreaks.Minepacks.Bukkit.API.Callback;
import at.pcgamingfreaks.Minepacks.Bukkit.Backpack;
import at.pcgamingfreaks.Minepacks.Bukkit.Database.UnCacheStrategies.OnDisconnect;
import at.pcgamingfreaks.Minepacks.Bukkit.Database.UnCacheStrategies.UnCacheStrategie;
import at.pcgamingfreaks.Minepacks.Bukkit.Minepacks;
import at.pcgamingfreaks.PluginLib.Bukkit.PluginLib;
import at.pcgamingfreaks.PluginLib.Database.DatabaseConnectionPool;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Database implements Listener
{
	protected static final String START_UUID_UPDATE = "Start updating database to UUIDs ...", UUIDS_UPDATED = "Updated %d accounts to UUIDs.";

	protected final Minepacks plugin;
	protected final InventorySerializer itsSerializer;
	protected final boolean useUUIDs, bungeeCordMode;
	protected boolean useUUIDSeparators;
	protected long maxAge;
	private final Map<OfflinePlayer, Backpack> backpacks = new ConcurrentHashMap<>();
	private final UnCacheStrategie unCacheStrategie;
	private final File backupFolder;

	public Database(Minepacks mp)
	{
		plugin = mp;
		itsSerializer = new InventorySerializer(plugin.getLogger());
		useUUIDSeparators = plugin.getConfiguration().getUseUUIDSeparators();
		useUUIDs = plugin.getConfiguration().getUseUUIDs();
		bungeeCordMode = plugin.getConfiguration().isBungeeCordModeEnabled();
		maxAge = plugin.getConfiguration().getAutoCleanupMaxInactiveDays();
		unCacheStrategie = bungeeCordMode ? new OnDisconnect(this) : UnCacheStrategie.getUnCacheStrategie(this);
		backupFolder = new File(this.plugin.getDataFolder(), "backups");
	}

	public void init()
	{
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	public void close()
	{
		HandlerList.unregisterAll(this);
		backpacks.forEach((key, value) -> value.closeAll());
		backpacks.clear();
		unCacheStrategie.close();
	}

	public static Database getDatabase(Minepacks plugin)
	{
		Database database;
		switch(plugin.getConfiguration().getDatabaseType().toLowerCase())
		{
			case "mysql":
				database = new MySQL(plugin); break;
			case "flat":
			case "file":
			case "files":
				database = new Files(plugin); break;
			case "external":
			case "global":
			case "shared":
				DatabaseConnectionPool pool = PluginLib.getInstance().getDatabaseConnectionPool();
				if(pool == null)
				{
					plugin.getLogger().warning(ConsoleColor.RED + "The shared connection pool is not initialized correctly!" + ConsoleColor.RESET);
					return null;
				}
				switch(pool.getDatabaseType().toLowerCase())
				{
					case "mysql": database = new MySQLShared(plugin, pool); break;
					case "sqlite": database = new SQLiteShared(plugin, pool); break;
					default: plugin.getLogger().warning(ConsoleColor.RED + "The database type of the shared pool is currently not supported!" + ConsoleColor.RESET); return null;
				}
				break;
			case "sqlite":
			default:
				database = new SQLite(plugin);
		}
		database.init();
		return database;
	}

	public void backup(@NotNull Backpack backpack)
	{
		writeBackup(backpack.getOwner().getName(), getPlayerNameOrUUID(backpack.getOwner()), itsSerializer.getUsedSerializer(), itsSerializer.serialize(backpack.getInventory()));
	}

	protected void writeBackup(@Nullable String userName, @NonNls String userIdentifier, final int usedSerializer, final @NotNull byte[] data)
	{
		if(userIdentifier.equalsIgnoreCase(userName)) userName = null;
		if(userName != null) userIdentifier = userName + "_" + userIdentifier;
		final File save = new File(backupFolder, userIdentifier + "_" + System.currentTimeMillis() + Files.EXT);
		try(FileOutputStream fos = new FileOutputStream(save))
		{
			fos.write(usedSerializer);
			fos.write(data);
			plugin.getLogger().info("Backup of the backpack has been created: " + save.getAbsolutePath());
		}
		catch(Exception e)
		{
			plugin.getLogger().warning("Failed to write backup! Error: " + e.getMessage());
		}
	}

	public @Nullable ItemStack[] loadBackup(final String backupName)
	{
		File backup = new File(backupFolder, backupName + Files.EXT);
		return Files.readFile(itsSerializer, backup, plugin.getLogger());
	}

	public Collection<String> getBackups()
	{
		File[] files = backupFolder.listFiles((dir, name) -> name.endsWith(Files.EXT));
		List<String> backups = new LinkedList<>();
		if(files != null)
		{
			for(File file : files)
			{
				if(!file.isFile()) continue;
				backups.add(file.getName().replaceAll(Files.EXT_REGEX, ""));
			}
		}
		return backups;
	}

	protected String getPlayerNameOrUUID(OfflinePlayer player)
	{
		if(useUUIDs)
		{
			return (useUUIDSeparators) ? player.getUniqueId().toString() : player.getUniqueId().toString().replace("-", "");
		}
		else
		{
			return player.getName();
		}
	}

	protected String getPlayerFormattedUUID(OfflinePlayer player)
	{
		if(useUUIDs)
		{
			return (useUUIDSeparators) ? player.getUniqueId().toString() : player.getUniqueId().toString().replace("-", "");
		}
		return null;
	}

	public @NotNull Collection<Backpack> getLoadedBackpacks()
	{
		return backpacks.values();
	}

	/**
	 * Gets a backpack for a player. This only includes backpacks that are cached! Do not use it unless you are sure that you only want to use cached data!
	 *
	 * @param player The player who's backpack should be retrieved.
	 * @return The backpack for the player. null if the backpack is not in the cache.
	 */
	public @Nullable Backpack getBackpack(@Nullable OfflinePlayer player)
	{
		return (player == null) ? null : backpacks.get(player);
	}

	public void getBackpack(final OfflinePlayer player, final Callback<Backpack> callback)
	{
		if(player == null)
		{
			return;
		}
		Backpack lbp = backpacks.get(player);
		if(lbp == null)
		{
			loadBackpack(player, new Callback<Backpack>()
			{
				@Override
				public void onResult(Backpack backpack)
				{
					backpacks.put(player, backpack);
					callback.onResult(backpack);
				}

				@Override
				public void onFail()
				{
					Backpack backpack = new Backpack(player);
					backpacks.put(player, backpack);
					callback.onResult(backpack);
				}
			});
		}
		else
		{
			callback.onResult(lbp);
		}
	}

	public void unloadBackpack(Backpack backpack)
	{
		backpacks.remove(backpack.getOwner());
	}

	public void asyncLoadBackpack(final OfflinePlayer player)
	{
		if(player != null && backpacks.get(player) == null)
		{
			loadBackpack(player, new Callback<Backpack>()
			{
				@Override
				public void onResult(Backpack backpack)
				{
					backpacks.put(player, backpack);
				}

				@Override
				public void onFail()
				{
					backpacks.put(player, new Backpack(player));
				}
			});
		}
	}

	@EventHandler
	public void onPlayerLoginEvent(PlayerJoinEvent event)
	{
		updatePlayerAndLoadBackpack(event.getPlayer());
	}

	// DB Functions
	public void updatePlayerAndLoadBackpack(Player player)
	{
		updatePlayer(player);
		if(!bungeeCordMode) asyncLoadBackpack(player);
	}

	public abstract void updatePlayer(Player player);

	public abstract void saveBackpack(Backpack backpack);

	public void syncCooldown(Player player, long time) {}

	public void getCooldown(final Player player, final Callback<Long> callback) {}

	protected abstract void loadBackpack(final OfflinePlayer player, final Callback<Backpack> callback);
}