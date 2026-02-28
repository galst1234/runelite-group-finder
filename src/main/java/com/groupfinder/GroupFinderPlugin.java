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
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.Player;
import net.runelite.api.events.FriendsChatChanged;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.FriendsChatMemberLeft;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
	private ClientThread clientThread;

	@Inject
	private GroupFinderConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private GroupFinderClient groupFinderClient;

	@Inject
	private ChatMessageManager chatMessageManager;

	private GroupFinderPanelView panel;
	private NavigationButton navButton;
	private ScheduledExecutorService executorService;
	private ScheduledFuture<?> pollFuture;
	private Activity currentFilter;
	private volatile boolean inFriendsChat;
	private volatile Runnable fcStatusCallback;
	private volatile String currentFcName;
	private volatile int currentFcMemberCount;
	private volatile String activeGroupId;

	@Override
	protected void startUp() throws Exception
	{
		GroupFinderPanel concretePanel = injector.getInstance(GroupFinderPanel.class);
		panel = concretePanel;

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Group Finder")
			.icon(icon)
			.priority(7)
			.panel(concretePanel)
			.build();

		clientToolbar.addNavigation(navButton);

		executorService = Executors.newSingleThreadScheduledExecutor();
		startPolling();

		clientThread.invokeLater(() ->
		{
			inFriendsChat = client.getFriendsChatManager() != null;
			FriendsChatManager fcm = client.getFriendsChatManager();
			if (fcm != null)
			{
				currentFcName = normalizeName(fcm.getOwner());
				currentFcMemberCount = fcm.getMembers().length;
			}
		});

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
			listing.setPlayerName(normalizeName(localPlayer.getName()));
		}

		if (listing.getPlayerName() == null || listing.getPlayerName().isEmpty())
		{
			panel.showError("You must be logged in to create a group");
			return;
		}

		if (config.groupManagementMode() == GroupManagementMode.FRIENDS_CHAT)
		{
			FriendsChatManager fcm = client.getFriendsChatManager();
			if (fcm == null)
			{
				panel.showError("Join a Friends Chat before creating a group");
				return;
			}
			listing.setFriendsChatName(normalizeName(fcm.getOwner()));
		}

		executorService.execute(() ->
		{
			GroupListing created = groupFinderClient.createGroup(listing);
			if (created != null)
			{
				activeGroupId = created.getId();
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
				if (id != null && id.equals(activeGroupId))
				{
					activeGroupId = null;
				}
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
			return normalizeName(localPlayer.getName());
		}
		return null;
	}

	private static String normalizeName(String name)
	{
		return name.replace('\u00A0', ' ');
	}

	void joinFriendsChat(String fcName)
	{
		log.debug("Attempting to join FC: {}", fcName);
		if (fcName == null || fcName.isEmpty())
		{
			log.debug("FC name is empty, not joining");
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				log.debug("Not logged in, cannot join FC");
				return;
			}

			// Widget 162,42 (Chatbox.MES_TEXT) is the text label shown in the
			// "Enter channel name" dialog. If it's visible and contains the
			// FC join prompt, the user has the dialog open already.
			Widget chatboxTextWidget = client.getWidget(162, 42);
			log.debug("Chatbox text widget: {}", chatboxTextWidget);

			boolean dialogOpen = chatboxTextWidget != null
				&& !chatboxTextWidget.isHidden()
				&& chatboxTextWidget.getText() != null
				&& chatboxTextWidget.getText().contains("Enter the player name");

			log.debug(
					"Dialog state: hidden={}, text={}",
					chatboxTextWidget != null ? chatboxTextWidget.isHidden() : "null",
					chatboxTextWidget != null ? chatboxTextWidget.getText() : "null"
			);

			if (dialogOpen)
			{
				log.debug("Joining FC: {}", fcName);
				SwingUtilities.invokeLater(() ->
				{
					Canvas canvas = client.getCanvas();
					long t = System.currentTimeMillis();
					// Clear any stale text already in the input box (FC names are ≤12 chars)
					for (int i = 0; i < 20; i++)
					{
						canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED,  t, 0, KeyEvent.VK_BACK_SPACE, '\b'));
						canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED,    t, 0, 0,                      '\b'));
						canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t, 0, KeyEvent.VK_BACK_SPACE, '\b'));
					}
					// Type the FC name character by character
					for (char c : fcName.toCharArray())
					{
						canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED, t, 0, 0, c));
					}
					// Submit
					canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED,  t, 0, KeyEvent.VK_ENTER, '\n'));
					canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED,    t, 0, 0,                 '\n'));
					canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t, 0, KeyEvent.VK_ENTER, '\n'));
				});
			}
			else
			{
				log.debug("Join dialog not open, cannot join FC");
				chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.GAMEMESSAGE)
					.value("Group Finder: Open the Friends Chat 'Join' dialog first, then click Join FC again.")
					.build());
			}
		});
	}

	boolean isInFriendsChat()
	{
		return inFriendsChat;
	}

	GroupManagementMode getGroupManagementMode()
	{
		return config.groupManagementMode();
	}

	String getCurrentFcName()
	{
		return currentFcName;
	}

	int getCurrentFcMemberCount()
	{
		return currentFcMemberCount;
	}

	String getActiveGroupId()
	{
		return activeGroupId;
	}

	void setFcStatusCallback(Runnable r)
	{
		fcStatusCallback = r;
	}

	@Subscribe
	public void onFriendsChatChanged(FriendsChatChanged event)
	{
		inFriendsChat = event.isJoined();
		if (event.isJoined())
		{
			FriendsChatManager fcm = client.getFriendsChatManager();
			if (fcm != null)
			{
				currentFcName = normalizeName(fcm.getOwner());
				currentFcMemberCount = fcm.getMembers().length;
			}
		}
		else
		{
			currentFcName = null;
			currentFcMemberCount = 0;
		}
		Runnable cb = fcStatusCallback;
		if (cb != null)
		{
			SwingUtilities.invokeLater(cb);
		}
	}

	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event)
	{
		FriendsChatManager fcm = client.getFriendsChatManager();
		if (fcm != null)
		{
			currentFcMemberCount = fcm.getMembers().length;
		}
		Runnable cb = fcStatusCallback;
		if (cb != null)
		{
			SwingUtilities.invokeLater(cb);
		}
		autoUpdateGroupSize();
	}

	@Subscribe
	public void onFriendsChatMemberLeft(FriendsChatMemberLeft event)
	{
		FriendsChatManager fcm = client.getFriendsChatManager();
		if (fcm != null)
		{
			currentFcMemberCount = fcm.getMembers().length;
		}
		Runnable cb = fcStatusCallback;
		if (cb != null)
		{
			SwingUtilities.invokeLater(cb);
		}
		autoUpdateGroupSize();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			inFriendsChat = false;
			currentFcName = null;
			currentFcMemberCount = 0;
			activeGroupId = null;
			Runnable cb = fcStatusCallback;
			if (cb != null)
			{
				SwingUtilities.invokeLater(cb);
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("groupfinder".equals(event.getGroup()) && "groupManagementMode".equals(event.getKey()))
		{
			refreshListings();
		}
	}

	private void autoUpdateGroupSize()
	{
		if (config.groupManagementMode() == GroupManagementMode.FRIENDS_CHAT && activeGroupId != null)
		{
			updateGroupSize(activeGroupId, currentFcMemberCount);
		}
	}

	@Provides
	GroupFinderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GroupFinderConfig.class);
	}
}
