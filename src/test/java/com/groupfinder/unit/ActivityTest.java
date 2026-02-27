package com.groupfinder.unit;

import com.groupfinder.Activity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityTest
{
	@ParameterizedTest
	@EnumSource(Activity.class)
	void toString_equalsDisplayName(Activity activity)
	{
		// toString() must return the human-readable display name, not the enum constant name
		assertThat(activity.toString()).isEqualTo(activity.getDisplayName());
	}

	@ParameterizedTest
	@EnumSource(Activity.class)
	void displayName_isNotNullOrBlank(Activity activity)
	{
		// Every activity must have a non-null, non-blank display name
		assertThat(activity.getDisplayName())
			.isNotNull()
			.isNotBlank();
	}
}
