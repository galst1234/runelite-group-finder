package com.groupfinder;

/**
 * Public test-data builder for GroupListing.
 * Public visibility is required so that tests in sub-packages
 * (e.g. com.groupfinder.client) can import it without duplication.
 */
public class GroupListingFixture
{
	public static GroupListing listing()
	{
		GroupListing g = new GroupListing();
		g.setId("test-id");
		g.setPlayerName("Alice");
		g.setFriendsChatName("AliceFC");
		g.setActivity(Activity.CHAMBERS_OF_XERIC);
		g.setCurrentSize(1);
		g.setMaxSize(3);
		g.setDescription("Test description");
		return g;
	}
}
