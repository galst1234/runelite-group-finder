package com.groupfinder;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.SwingUtilities;

import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.FriendsChatChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// LENIENT: executorService and clientThread doAnswer stubs in @BeforeEach are
// intentionally unused in tests that never reach an async code path (e.g. early-return
// guard tests). Per-test stubs inside @Test bodies are always used.
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class GroupFinderPluginBehaviorTest
{
	@Mock
	Client mockClient;

	@Mock
	ClientThread mockClientThread;

	@Mock
	GroupFinderConfig mockConfig;

	@Mock
	GroupFinderClient mockGroupFinderClient;

	@Mock
	ChatMessageManager mockChatMessageManager;

	@Mock
	GroupFinderPanelView mockPanel;

	@Mock
	ScheduledExecutorService mockExecutor;

	@Mock
	Player mockPlayer;

	@Mock
	FriendsChatManager mockFcm;

	private GroupFinderPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new GroupFinderPlugin();
		inject("client",             mockClient);
		inject("clientThread",       mockClientThread);
		inject("config",             mockConfig);
		inject("groupFinderClient",  mockGroupFinderClient);
		inject("chatMessageManager", mockChatMessageManager);
		inject("panel",              mockPanel);
		inject("executorService",    mockExecutor);

		// Make executor synchronous so async lambdas run inline
		doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
			.when(mockExecutor).execute(any(Runnable.class));

		// Make clientThread synchronous so invokeLater runs inline
		doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
			.when(mockClientThread).invokeLater(any(Runnable.class));
	}

	private void inject(String fieldName, Object value) throws Exception
	{
		Field f = GroupFinderPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	// -------------------------------------------------------------------------
	// createGroup
	// -------------------------------------------------------------------------

	@Nested
	class CreateGroup
	{
		@Test
		void whenNoLocalPlayer_showsLoginError()
		{
			// Arrange
			when(mockClient.getLocalPlayer()).thenReturn(null);
			GroupListing listing = GroupListingFixture.listing();
			listing.setPlayerName(null);

			// Act
			plugin.createGroup(listing);

			// Assert
			verify(mockPanel).showError("You must be logged in to create a group");
			verify(mockGroupFinderClient, never()).createGroup(any());
		}

		@Test
		void whenPlayerNameIsEmpty_showsLoginError()
		{
			// Arrange — player exists but getName() returns empty string
			when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
			when(mockPlayer.getName()).thenReturn("");
			GroupListing listing = GroupListingFixture.listing();
			listing.setPlayerName(null);

			// Act
			plugin.createGroup(listing);

			// Assert
			verify(mockPanel).showError("You must be logged in to create a group");
			verify(mockGroupFinderClient, never()).createGroup(any());
		}

		@Test
		void whenNoFriendsChat_showsFcError()
		{
			// Arrange
			when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
			when(mockPlayer.getName()).thenReturn("Alice");
			when(mockClient.getFriendsChatManager()).thenReturn(null);
			GroupListing listing = GroupListingFixture.listing();

			// Act
			plugin.createGroup(listing);

			// Assert
			verify(mockPanel).showError("Join a Friends Chat before creating a group");
			verify(mockGroupFinderClient, never()).createGroup(any());
		}

		@Test
		void normalizesNbspInPlayerName()
		{
			// Arrange — player name contains non-breaking spaces (RuneLite encoding)
			when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
			when(mockPlayer.getName()).thenReturn("Bob\u00A0Smith");
			when(mockClient.getFriendsChatManager()).thenReturn(mockFcm);
			when(mockFcm.getOwner()).thenReturn("OwnerFC");
			GroupListing listing = new GroupListing();
			listing.setActivity(Activity.OTHER);
			listing.setMaxSize(4);
			listing.setCurrentSize(1);
			when(mockGroupFinderClient.createGroup(any())).thenReturn(GroupListingFixture.listing());
			when(mockGroupFinderClient.getGroups(any())).thenReturn(Collections.emptyList());

			// Act
			plugin.createGroup(listing);

			// Assert — normalized name uses regular ASCII space
			assertThat(listing.getPlayerName()).isEqualTo("Bob Smith");
		}

		@Test
		void normalizesNbspInFcOwnerName()
		{
			// Arrange — FC owner name has non-breaking space
			when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
			when(mockPlayer.getName()).thenReturn("Alice");
			when(mockClient.getFriendsChatManager()).thenReturn(mockFcm);
			when(mockFcm.getOwner()).thenReturn("FC\u00A0Owner");
			GroupListing listing = new GroupListing();
			listing.setActivity(Activity.OTHER);
			listing.setMaxSize(4);
			listing.setCurrentSize(1);
			when(mockGroupFinderClient.createGroup(any())).thenReturn(GroupListingFixture.listing());
			when(mockGroupFinderClient.getGroups(any())).thenReturn(Collections.emptyList());

			// Act
			plugin.createGroup(listing);

			// Assert — normalized FC name uses regular ASCII space
			assertThat(listing.getFriendsChatName()).isEqualTo("FC Owner");
		}

		@Test
		void whenApiSucceeds_panelIsRefreshed()
		{
			// Arrange
			when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
			when(mockPlayer.getName()).thenReturn("Alice");
			when(mockClient.getFriendsChatManager()).thenReturn(mockFcm);
			when(mockFcm.getOwner()).thenReturn("AliceFC");
			GroupListing listing = GroupListingFixture.listing();
			when(mockGroupFinderClient.createGroup(any())).thenReturn(GroupListingFixture.listing());
			List<GroupListing> refreshed = List.of(GroupListingFixture.listing());
			when(mockGroupFinderClient.getGroups(any())).thenReturn(refreshed);

			// Act
			plugin.createGroup(listing);

			// Assert — panel receives the refreshed listings
			verify(mockPanel).updateListings(refreshed);
		}

		@Test
		void whenApiFails_showsErrorAndDoesNotRefresh()
		{
			// Arrange
			when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
			when(mockPlayer.getName()).thenReturn("Alice");
			when(mockClient.getFriendsChatManager()).thenReturn(mockFcm);
			when(mockFcm.getOwner()).thenReturn("AliceFC");
			GroupListing listing = GroupListingFixture.listing();
			when(mockGroupFinderClient.createGroup(any())).thenReturn(null);

			// Act
			plugin.createGroup(listing);

			// Assert — error shown; panel must NOT be refreshed
			verify(mockPanel).showError("Failed to create group");
			verify(mockGroupFinderClient, never()).getGroups(any());
		}
	}

	// -------------------------------------------------------------------------
	// deleteGroup
	// -------------------------------------------------------------------------

	@Nested
	class DeleteGroup
	{
		@Test
		void whenApiSucceeds_panelIsRefreshed()
		{
			// Arrange
			when(mockGroupFinderClient.deleteGroup("test-id")).thenReturn(true);
			List<GroupListing> refreshed = List.of(GroupListingFixture.listing());
			when(mockGroupFinderClient.getGroups(any())).thenReturn(refreshed);

			// Act
			plugin.deleteGroup("test-id");

			// Assert
			verify(mockPanel).updateListings(refreshed);
		}

		@Test
		void whenApiFails_showsErrorAndDoesNotRefresh()
		{
			// Arrange
			when(mockGroupFinderClient.deleteGroup("test-id")).thenReturn(false);

			// Act
			plugin.deleteGroup("test-id");

			// Assert — error shown; no panel refresh
			verify(mockPanel).showError("Failed to delete group");
			verify(mockGroupFinderClient, never()).getGroups(any());
		}
	}

	// -------------------------------------------------------------------------
	// updateGroupSize
	// -------------------------------------------------------------------------

	@Nested
	class UpdateGroupSize
	{
		@Test
		void whenApiSucceeds_panelIsRefreshed()
		{
			// Arrange
			when(mockGroupFinderClient.updateGroup(eq("test-id"), any())).thenReturn(GroupListingFixture.listing());
			List<GroupListing> refreshed = List.of(GroupListingFixture.listing());
			when(mockGroupFinderClient.getGroups(any())).thenReturn(refreshed);

			// Act
			plugin.updateGroupSize("test-id", 3);

			// Assert
			verify(mockPanel).updateListings(refreshed);
		}

		@Test
		void whenApiFails_showsErrorAndDoesNotRefresh()
		{
			// Arrange
			when(mockGroupFinderClient.updateGroup(eq("test-id"), any())).thenReturn(null);

			// Act
			plugin.updateGroupSize("test-id", 3);

			// Assert — error shown; no panel refresh
			verify(mockPanel).showError("Failed to update group");
			verify(mockGroupFinderClient, never()).getGroups(any());
		}
	}

	// -------------------------------------------------------------------------
	// refreshListings
	// -------------------------------------------------------------------------

	@Nested
	class RefreshListings
	{
		@Test
		void whenNonEmptyResult_panelShowsListings()
		{
			// Arrange
			List<GroupListing> listings = List.of(GroupListingFixture.listing());
			when(mockGroupFinderClient.getGroups(any())).thenReturn(listings);

			// Act
			plugin.refreshListings();

			// Assert
			verify(mockPanel).updateListings(listings);
		}

		@Test
		void whenEmptyResult_panelShowsEmptyListNotError()
		{
			// Arrange
			when(mockGroupFinderClient.getGroups(any())).thenReturn(Collections.emptyList());

			// Act
			plugin.refreshListings();

			// Assert — empty list -> updateListings with empty, NOT showError
			verify(mockPanel).updateListings(Collections.emptyList());
			verify(mockPanel, never()).showError(any());
		}

		@Test
		void whenClientThrows_showsExactConnectionErrorMessage()
		{
			// Arrange
			when(mockGroupFinderClient.getGroups(any())).thenThrow(new RuntimeException("timeout"));

			// Act
			plugin.refreshListings();

			// Assert — exact literal error message, not a partial match
			verify(mockPanel).showError("Could not connect to server");
		}
	}

	// -------------------------------------------------------------------------
	// onFilterChanged
	// -------------------------------------------------------------------------

	@Nested
	class OnFilterChanged
	{
		@Test
		void passesActivityToClient()
		{
			// Arrange
			when(mockGroupFinderClient.getGroups(Activity.CHAMBERS_OF_XERIC))
				.thenReturn(List.of(GroupListingFixture.listing()));

			// Act
			plugin.onFilterChanged(Activity.CHAMBERS_OF_XERIC);

			// Assert — the exact activity is forwarded to the client
			verify(mockGroupFinderClient).getGroups(Activity.CHAMBERS_OF_XERIC);
		}
	}

	// -------------------------------------------------------------------------
	// onFriendsChatChanged
	// -------------------------------------------------------------------------

	@Nested
	class OnFriendsChatChanged
	{
		@Test
		void whenJoined_setsInFriendsChat() throws Exception
		{
			// Arrange
			FriendsChatChanged event = mock(FriendsChatChanged.class);
			when(event.isJoined()).thenReturn(true);

			// Act
			plugin.onFriendsChatChanged(event);
			SwingUtilities.invokeAndWait(() -> {});

			// Assert
			assertThat(plugin.isInFriendsChat()).isTrue();
		}

		@Test
		void whenLeft_clearsInFriendsChat() throws Exception
		{
			// Arrange: first join to establish state, then leave
			FriendsChatChanged joinEvent = mock(FriendsChatChanged.class);
			when(joinEvent.isJoined()).thenReturn(true);
			plugin.onFriendsChatChanged(joinEvent);  // Arrange: get into joined state

			FriendsChatChanged leaveEvent = mock(FriendsChatChanged.class);
			when(leaveEvent.isJoined()).thenReturn(false);

			// Act
			plugin.onFriendsChatChanged(leaveEvent);
			SwingUtilities.invokeAndWait(() -> {});

			// Assert
			assertThat(plugin.isInFriendsChat()).isFalse();
		}

		@Test
		void whenJoined_invokesCallback() throws Exception
		{
			// Arrange
			Runnable callback = mock(Runnable.class);
			plugin.setFcStatusCallback(callback);
			FriendsChatChanged event = mock(FriendsChatChanged.class);
			when(event.isJoined()).thenReturn(true);

			// Act
			plugin.onFriendsChatChanged(event);
			SwingUtilities.invokeAndWait(() -> {});

			// Assert
			verify(callback).run();
		}

		@Test
		void whenNoCallback_doesNotThrow() throws Exception
		{
			// Arrange — no callback set (null)
			plugin.setFcStatusCallback(null);
			FriendsChatChanged event = mock(FriendsChatChanged.class);
			when(event.isJoined()).thenReturn(true);

			// Act + Assert — must not throw NullPointerException
			plugin.onFriendsChatChanged(event);
			SwingUtilities.invokeAndWait(() -> {});
		}
	}

	// -------------------------------------------------------------------------
	// onGameStateChanged
	// -------------------------------------------------------------------------

	@Nested
	class OnGameStateChanged
	{
		@ParameterizedTest
		@EnumSource(value = GameState.class, names = {"LOGIN_SCREEN", "HOPPING"})
		void disconnectingStates_clearInFriendsChat(GameState state) throws Exception
		{
			// Arrange: set inFriendsChat to true first
			FriendsChatChanged joinEvent = mock(FriendsChatChanged.class);
			when(joinEvent.isJoined()).thenReturn(true);
			plugin.onFriendsChatChanged(joinEvent);

			GameStateChanged event = mock(GameStateChanged.class);
			when(event.getGameState()).thenReturn(state);

			// Act
			plugin.onGameStateChanged(event);
			SwingUtilities.invokeAndWait(() -> {});

			// Assert
			assertThat(plugin.isInFriendsChat()).isFalse();
		}

		@ParameterizedTest
		@EnumSource(value = GameState.class, names = {"LOGIN_SCREEN", "HOPPING"})
		void disconnectingStates_invokeCallback(GameState state) throws Exception
		{
			// Arrange
			Runnable callback = mock(Runnable.class);
			plugin.setFcStatusCallback(callback);
			GameStateChanged event = mock(GameStateChanged.class);
			when(event.getGameState()).thenReturn(state);

			// Act
			plugin.onGameStateChanged(event);
			SwingUtilities.invokeAndWait(() -> {});

			// Assert
			verify(callback).run();
		}

		@Test
		void loggedInState_doesNotClearInFriendsChat() throws Exception
		{
			// Arrange: set inFriendsChat to true first
			FriendsChatChanged joinEvent = mock(FriendsChatChanged.class);
			when(joinEvent.isJoined()).thenReturn(true);
			plugin.onFriendsChatChanged(joinEvent);

			GameStateChanged event = mock(GameStateChanged.class);
			when(event.getGameState()).thenReturn(GameState.LOGGED_IN);

			// Act
			plugin.onGameStateChanged(event);
			SwingUtilities.invokeAndWait(() -> {});

			// Assert — LOGGED_IN does not change FC state
			assertThat(plugin.isInFriendsChat()).isTrue();
		}
	}

	// -------------------------------------------------------------------------
	// joinFriendsChat
	// -------------------------------------------------------------------------

	@Nested
	class JoinFriendsChat
	{
		@Test
		void whenFcNameIsNull_returnsEarlyWithNoSideEffects()
		{
			// Act
			plugin.joinFriendsChat(null);

			// Assert — clientThread never invoked: nothing happens
			verify(mockClientThread, never()).invokeLater(any(Runnable.class));
		}

		@Test
		void whenFcNameIsEmpty_returnsEarlyWithNoSideEffects()
		{
			// Act
			plugin.joinFriendsChat("");

			// Assert — clientThread never invoked: nothing happens
			verify(mockClientThread, never()).invokeLater(any(Runnable.class));
		}

		@Test
		void whenNotLoggedIn_doesNotSendChatMessage()
		{
			// Arrange — game state is not LOGGED_IN
			when(mockClient.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

			// Act
			plugin.joinFriendsChat("SomeFC");

			// Assert — the guard returns early; no message queued
			verify(mockChatMessageManager, never()).queue(any());
		}

		@Test
		void whenDialogNotOpen_sendsChatGuideMessage()
		{
			// Arrange — logged in but dialog widget is null (dialog not open)
			when(mockClient.getGameState()).thenReturn(GameState.LOGGED_IN);
			// Widget 162,42 = Chatbox.MES_TEXT (the "Enter channel name" dialog label)
			when(mockClient.getWidget(162, 42)).thenReturn(null);

			// Act
			plugin.joinFriendsChat("SomeFC");

			// Assert — guide message queued to tell user to open dialog
			verify(mockChatMessageManager).queue(any());
		}

		@Test
		void whenDialogIsOpen_doesNotSendChatMessage() throws Exception
		{
			// Arrange — widget visible with the expected text; real canvas so key dispatch works
			when(mockClient.getGameState()).thenReturn(GameState.LOGGED_IN);
			Widget mockWidget = mock(Widget.class);
			// Widget 162,42 = Chatbox.MES_TEXT (the "Enter channel name" dialog label)
			when(mockClient.getWidget(162, 42)).thenReturn(mockWidget);
			when(mockWidget.isHidden()).thenReturn(false);
			when(mockWidget.getText()).thenReturn("Enter the player name");
			// Provide a real Canvas so key dispatch does not NPE
			when(mockClient.getCanvas()).thenReturn(mock(java.awt.Canvas.class));

			// Act
			plugin.joinFriendsChat("SomeFC");
			// Flush any SwingUtilities.invokeLater calls spawned by joinFriendsChat
			SwingUtilities.invokeAndWait(() -> {});

			// Assert — guide message must NOT be queued when dialog is already open
			verify(mockChatMessageManager, never()).queue(any());
		}
	}

	// -------------------------------------------------------------------------
	// getLocalPlayerName
	// -------------------------------------------------------------------------

	@Nested
	class GetLocalPlayerName
	{
		@Test
		void whenNoPlayer_returnsNull()
		{
			// Arrange
			when(mockClient.getLocalPlayer()).thenReturn(null);

			// Act + Assert
			assertThat(plugin.getLocalPlayerName()).isNull();
		}

		@Test
		void normalizesNbspInName()
		{
			// Arrange — RuneLite encodes spaces as non-breaking space \u00A0
			when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
			when(mockPlayer.getName()).thenReturn("Bob\u00A0Smith");

			// Act
			String result = plugin.getLocalPlayerName();

			// Assert — returned name uses regular ASCII space
			assertThat(result).isEqualTo("Bob Smith");
		}
	}
}
