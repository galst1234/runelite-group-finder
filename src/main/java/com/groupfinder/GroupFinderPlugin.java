/*
 * Copyright (c) 2025, galst
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.groupfinder;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Group Finder"
)
public class GroupFinderPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private GroupFinderConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private GroupFinderClient groupFinderClient;

	private GroupFinderPanel panel;
	private NavigationButton navButton;
	private ScheduledExecutorService executorService;
	private ScheduledFuture<?> pollFuture;
	private Activity currentFilter;

	@Override
	protected void startUp() throws Exception
	{
		panel = injector.getInstance(GroupFinderPanel.class);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Group Finder")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		executorService = Executors.newSingleThreadScheduledExecutor();
		startPolling();

		log.debug("Group Finder started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(false);
		}

		if (executorService != null)
		{
			executorService.shutdown();
		}

		clientToolbar.removeNavigation(navButton);

		log.debug("Group Finder stopped");
	}

	private void startPolling()
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(false);
		}

		pollFuture = executorService.scheduleWithFixedDelay(
			this::pollGroups,
			0,
			config.pollInterval(),
			TimeUnit.SECONDS
		);
	}

	private void pollGroups()
	{
		try
		{
			List<GroupListing> listings = groupFinderClient.getGroups(currentFilter);
			panel.updateListings(listings);
		}
		catch (Exception e)
		{
			log.warn("Error polling groups", e);
			panel.showError("Could not connect to server");
		}
	}

	void refreshListings()
	{
		executorService.execute(this::pollGroups);
	}

	void onFilterChanged(Activity activity)
	{
		currentFilter = activity;
		refreshListings();
	}

	void createGroup(GroupListing listing)
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null && localPlayer.getName() != null)
		{
			listing.setPlayerName(localPlayer.getName());
		}

		if (listing.getPlayerName() == null || listing.getPlayerName().isEmpty())
		{
			panel.showError("You must be logged in to create a group");
			return;
		}

		executorService.execute(() ->
		{
			GroupListing created = groupFinderClient.createGroup(listing);
			if (created != null)
			{
				pollGroups();
			}
			else
			{
				panel.showError("Failed to create group");
			}
		});
	}

	void deleteGroup(String id)
	{
		executorService.execute(() ->
		{
			boolean deleted = groupFinderClient.deleteGroup(id);
			if (deleted)
			{
				pollGroups();
			}
			else
			{
				panel.showError("Failed to delete group");
			}
		});
	}

	void updateGroupSize(String id, int newSize)
	{
		executorService.execute(() ->
		{
			java.util.Map<String, Object> fields = new java.util.HashMap<>();
			fields.put("currentSize", newSize);
			GroupListing result = groupFinderClient.updateGroup(id, fields);
			if (result != null)
			{
				pollGroups();
			}
			else
			{
				panel.showError("Failed to update group");
			}
		});
	}

	String getLocalPlayerName()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null && localPlayer.getName() != null)
		{
			return localPlayer.getName();
		}
		return null;
	}

	@Provides
	GroupFinderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GroupFinderConfig.class);
	}
}
