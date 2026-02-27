package com.groupfinder.client;

import com.google.gson.Gson;
import com.groupfinder.Activity;
import com.groupfinder.GroupFinderClient;
import com.groupfinder.GroupFinderConfig;
import com.groupfinder.GroupListing;
import com.groupfinder.GroupListingFixture;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupFinderClientTest
{
	private MockWebServer server;
	private GroupFinderClient client;
	private Gson gson;

	@BeforeEach
	void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		gson = new Gson();
		GroupFinderConfig config = mock(GroupFinderConfig.class);
		when(config.serverUrl()).thenReturn("http://localhost:" + server.getPort());
		client = new GroupFinderClient(new OkHttpClient(), gson, config);
	}

	@AfterEach
	void tearDown()
	{
		try
		{
			server.shutdown();
		}
		catch (IOException ignored)
		{
		}
	}

	// -------------------------------------------------------------------------
	// getGroups
	// -------------------------------------------------------------------------

	@Nested
	class GetGroups
	{
		@Test
		void withNoFilter_omitsQueryParam() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setBody("[]").setResponseCode(200));

			// Act
			client.getGroups(null);

			// Assert — request URL has no activity query parameter
			RecordedRequest req = server.takeRequest();
			assertThat(req.getMethod()).isEqualTo("GET");
			assertThat(req.getPath()).isEqualTo("/api/groups");
		}

		@Test
		void withActivityFilter_appendsActivityQueryParam() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setBody("[]").setResponseCode(200));

			// Act
			client.getGroups(Activity.CHAMBERS_OF_XERIC);

			// Assert — enum constant name is used as the query param value
			RecordedRequest req = server.takeRequest();
			assertThat(req.getPath()).isEqualTo("/api/groups?activity=CHAMBERS_OF_XERIC");
		}

		@Test
		void onSuccess_parsesResponseIntoList() throws Exception
		{
			// Arrange
			GroupListing listing = GroupListingFixture.listing();
			String json = "[" + gson.toJson(listing) + "]";
			server.enqueue(new MockResponse().setBody(json).setResponseCode(200));

			// Act
			List<GroupListing> result = client.getGroups(null);

			// Assert
			assertThat(result).hasSize(1);
			assertThat(result.get(0).getPlayerName()).isEqualTo(listing.getPlayerName());
			assertThat(result.get(0).getActivity()).isEqualTo(listing.getActivity());
		}

		@Test
		void onHttpErrorResponse_returnsEmptyList() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setResponseCode(500));

			// Act
			List<GroupListing> result = client.getGroups(null);

			// Assert — no exception, just empty list
			assertThat(result).isEmpty();
		}

		@Test
		void onNetworkFailure_returnsEmptyList() throws IOException
		{
			// Arrange — shut down the server so the connection is refused
			server.shutdown();

			// Act
			List<GroupListing> result = client.getGroups(null);

			// Assert — no exception propagated; returns empty list
			assertThat(result).isEmpty();
		}

		@Test
		void onMalformedJson_propagatesRuntimeException() throws Exception
		{
			// Arrange — server returns invalid JSON
			server.enqueue(new MockResponse().setBody("NOT_JSON").setResponseCode(200));

			// Act + Assert — client must NOT silently swallow the parse failure
			assertThatThrownBy(() -> client.getGroups(null))
				.isInstanceOf(RuntimeException.class);
		}
	}

	// -------------------------------------------------------------------------
	// createGroup
	// -------------------------------------------------------------------------

	@Nested
	class CreateGroup
	{
		@Test
		void postsToCorrectEndpoint() throws Exception
		{
			// Arrange
			GroupListing listing = GroupListingFixture.listing();
			server.enqueue(new MockResponse()
				.setBody(gson.toJson(listing))
				.setResponseCode(201));

			// Act
			client.createGroup(listing);

			// Assert — correct HTTP verb and path
			RecordedRequest req = server.takeRequest();
			assertThat(req.getMethod()).isEqualTo("POST");
			assertThat(req.getPath()).isEqualTo("/api/groups");
		}

		@Test
		void serializedBodyContainsPlayerNameAndActivity() throws Exception
		{
			// Arrange
			GroupListing listing = GroupListingFixture.listing();
			server.enqueue(new MockResponse()
				.setBody(gson.toJson(listing))
				.setResponseCode(201));

			// Act
			client.createGroup(listing);

			// Assert — parse the request body and check field values
			RecordedRequest req = server.takeRequest();
			GroupListing sent = gson.fromJson(req.getBody().readUtf8(), GroupListing.class);
			assertThat(sent.getPlayerName()).isEqualTo(listing.getPlayerName());
			assertThat(sent.getActivity()).isEqualTo(listing.getActivity());
		}

		@Test
		void onSuccess_returnsParsedListing() throws Exception
		{
			// Arrange
			GroupListing listing = GroupListingFixture.listing();
			server.enqueue(new MockResponse()
				.setBody(gson.toJson(listing))
				.setResponseCode(201));

			// Act
			GroupListing result = client.createGroup(listing);

			// Assert
			assertThat(result).isNotNull();
			assertThat(result.getId()).isEqualTo(listing.getId());
		}

		@Test
		void onHttpErrorResponse_returnsNull() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setResponseCode(400));

			// Act
			GroupListing result = client.createGroup(GroupListingFixture.listing());

			// Assert — no exception, null returned on failure
			assertThat(result).isNull();
		}

		@Test
		void onNetworkFailure_returnsNull() throws IOException
		{
			// Arrange — shut down the server
			server.shutdown();

			// Act
			GroupListing result = client.createGroup(GroupListingFixture.listing());

			// Assert
			assertThat(result).isNull();
		}
	}

	// -------------------------------------------------------------------------
	// deleteGroup
	// -------------------------------------------------------------------------

	@Nested
	class DeleteGroup
	{
		@Test
		void sendsDeleteToCorrectPath() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setResponseCode(200));

			// Act
			client.deleteGroup("abc-123");

			// Assert — correct HTTP verb and path with ID
			RecordedRequest req = server.takeRequest();
			assertThat(req.getMethod()).isEqualTo("DELETE");
			assertThat(req.getPath()).isEqualTo("/api/groups/abc-123");
		}

		@Test
		void onSuccessResponse_returnsTrue() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setResponseCode(200));

			// Act + Assert
			assertThat(client.deleteGroup("abc-123")).isTrue();
		}

		@Test
		void onHttpErrorResponse_returnsFalse() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setResponseCode(404));

			// Act + Assert
			assertThat(client.deleteGroup("abc-123")).isFalse();
		}

		@Test
		void onNetworkFailure_returnsFalse() throws IOException
		{
			// Arrange — shut down the server
			server.shutdown();

			// Act + Assert
			assertThat(client.deleteGroup("abc-123")).isFalse();
		}
	}

	// -------------------------------------------------------------------------
	// updateGroup
	// -------------------------------------------------------------------------

	@Nested
	class UpdateGroup
	{
		@Test
		void sendsPatchToCorrectPath() throws Exception
		{
			// Arrange
			GroupListing listing = GroupListingFixture.listing();
			server.enqueue(new MockResponse()
				.setBody(gson.toJson(listing))
				.setResponseCode(200));

			// Act
			client.updateGroup("abc-123", Map.of("currentSize", 5));

			// Assert — correct HTTP verb and path with ID
			RecordedRequest req = server.takeRequest();
			assertThat(req.getMethod()).isEqualTo("PATCH");
			assertThat(req.getPath()).isEqualTo("/api/groups/abc-123");
		}

		@Test
		void serializedBodyContainsUpdatedField() throws Exception
		{
			// Arrange
			GroupListing listing = GroupListingFixture.listing();
			server.enqueue(new MockResponse()
				.setBody(gson.toJson(listing))
				.setResponseCode(200));
			Map<String, Object> fields = Map.of("currentSize", 5);

			// Act
			client.updateGroup("abc-123", fields);

			// Assert — parse the request body as a generic map and check the field
			RecordedRequest req = server.takeRequest();
			@SuppressWarnings("unchecked")
			Map<String, Object> sentFields = gson.fromJson(req.getBody().readUtf8(), Map.class);
			// Gson deserializes numbers as Double
			assertThat(((Number) sentFields.get("currentSize")).intValue()).isEqualTo(5);
		}

		@Test
		void onSuccess_returnsParsedListing() throws Exception
		{
			// Arrange
			GroupListing listing = GroupListingFixture.listing();
			server.enqueue(new MockResponse()
				.setBody(gson.toJson(listing))
				.setResponseCode(200));

			// Act
			GroupListing result = client.updateGroup("abc-123", Map.of("currentSize", 5));

			// Assert
			assertThat(result).isNotNull();
			assertThat(result.getId()).isEqualTo(listing.getId());
		}

		@Test
		void onHttpErrorResponse_returnsNull() throws Exception
		{
			// Arrange
			server.enqueue(new MockResponse().setResponseCode(400));

			// Act
			GroupListing result = client.updateGroup("abc-123", Map.of("currentSize", 5));

			// Assert
			assertThat(result).isNull();
		}

		@Test
		void onNetworkFailure_returnsNull() throws IOException
		{
			// Arrange — shut down the server
			server.shutdown();

			// Act
			GroupListing result = client.updateGroup("abc-123", Map.of("currentSize", 5));

			// Assert
			assertThat(result).isNull();
		}
	}
}
