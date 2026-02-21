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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
public class GroupFinderClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final Type GROUP_LIST_TYPE = new TypeToken<List<GroupListing>>()
	{
	}.getType();

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final GroupFinderConfig config;

	@Inject
	public GroupFinderClient(OkHttpClient okHttpClient, Gson gson, GroupFinderConfig config)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.config = config;
	}

	public List<GroupListing> getGroups(Activity filter)
	{
		HttpUrl.Builder urlBuilder = HttpUrl.parse(config.serverUrl() + "/api/groups").newBuilder();
		if (filter != null)
		{
			urlBuilder.addQueryParameter("activity", filter.name());
		}

		Request request = new Request.Builder()
			.url(urlBuilder.build())
			.get()
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				log.warn("Failed to fetch groups: HTTP {}", response.code());
				return Collections.emptyList();
			}

			String body = response.body().string();
			return gson.fromJson(body, GROUP_LIST_TYPE);
		}
		catch (IOException e)
		{
			log.warn("Failed to fetch groups", e);
			return Collections.emptyList();
		}
	}

	public GroupListing createGroup(GroupListing listing)
	{
		String json = gson.toJson(listing);
		RequestBody body = RequestBody.create(JSON, json);

		Request request = new Request.Builder()
			.url(config.serverUrl() + "/api/groups")
			.post(body)
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				log.warn("Failed to create group: HTTP {}", response.code());
				return null;
			}

			String responseBody = response.body().string();
			return gson.fromJson(responseBody, GroupListing.class);
		}
		catch (IOException e)
		{
			log.warn("Failed to create group", e);
			return null;
		}
	}

	public boolean deleteGroup(String id)
	{
		Request request = new Request.Builder()
			.url(config.serverUrl() + "/api/groups/" + id)
			.delete()
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				log.warn("Failed to delete group: HTTP {}", response.code());
				return false;
			}
			return true;
		}
		catch (IOException e)
		{
			log.warn("Failed to delete group", e);
			return false;
		}
	}

	public GroupListing updateGroup(String id, java.util.Map<String, Object> fields)
	{
		String json = gson.toJson(fields);
		RequestBody body = RequestBody.create(JSON, json);

		Request request = new Request.Builder()
			.url(config.serverUrl() + "/api/groups/" + id)
			.patch(body)
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				log.warn("Failed to update group: HTTP {}", response.code());
				return null;
			}

			String responseBody = response.body().string();
			return gson.fromJson(responseBody, GroupListing.class);
		}
		catch (IOException e)
		{
			log.warn("Failed to update group", e);
			return null;
		}
	}
}
