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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

@Slf4j
public class GroupFinderPanel extends PluginPanel
{
	private static final ImageIcon DELETE_ICON;

	static
	{
		final BufferedImage deleteImg = ImageUtil.loadImageResource(
			GroupFinderPanel.class, "/net/runelite/client/plugins/timetracking/delete_icon.png");
		DELETE_ICON = new ImageIcon(deleteImg);
	}

	private final GroupFinderPlugin plugin;

	private final JComboBox<String> activityFilter;
	private final JPanel listingsContainer;
	private final JLabel statusLabel;

	@Inject
	public GroupFinderPanel(GroupFinderPlugin plugin)
	{
		super(false);
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Top controls panel
		JPanel controlsPanel = new JPanel();
		controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
		controlsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controlsPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		// Activity filter
		activityFilter = new JComboBox<>();
		activityFilter.addItem("All Activities");
		for (Activity activity : Activity.values())
		{
			activityFilter.addItem(activity.getDisplayName());
		}
		activityFilter.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		activityFilter.addActionListener(e -> plugin.onFilterChanged(getSelectedActivity()));
		controlsPanel.add(activityFilter);

		// Buttons row
		JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
		buttonsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton refreshButton = new JButton("Refresh");
		refreshButton.addActionListener(e -> plugin.refreshListings());
		buttonsPanel.add(refreshButton);

		JButton createButton = new JButton("+ Create");
		createButton.addActionListener(e -> showCreateDialog());
		buttonsPanel.add(createButton);

		controlsPanel.add(buttonsPanel);

		add(controlsPanel, BorderLayout.NORTH);

		// Listings area
		listingsContainer = new JPanel();
		listingsContainer.setLayout(new BoxLayout(listingsContainer, BoxLayout.Y_AXIS));
		listingsContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(listingsContainer);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		add(scrollPane, BorderLayout.CENTER);

		// Status label at bottom
		statusLabel = new JLabel("Loading...");
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
		add(statusLabel, BorderLayout.SOUTH);
	}

	Activity getSelectedActivity()
	{
		int index = activityFilter.getSelectedIndex();
		if (index <= 0)
		{
			return null;
		}
		return Activity.values()[index - 1];
	}

	public void updateListings(List<GroupListing> listings)
	{
		SwingUtilities.invokeLater(() ->
		{
			listingsContainer.removeAll();

			if (listings == null || listings.isEmpty())
			{
				JLabel emptyLabel = new JLabel("No groups found");
				emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				emptyLabel.setBorder(new EmptyBorder(20, 10, 20, 10));
				listingsContainer.add(emptyLabel);
				statusLabel.setText("0 groups");
			}
			else
			{
				for (GroupListing listing : listings)
				{
					listingsContainer.add(buildListingCard(listing));
				}
				statusLabel.setText(listings.size() + " group" + (listings.size() != 1 ? "s" : ""));
			}

			listingsContainer.revalidate();
			listingsContainer.repaint();
		});
	}

	public void showError(String message)
	{
		SwingUtilities.invokeLater(() -> statusLabel.setText(message));
	}

	private JPanel buildListingCard(GroupListing listing)
	{
		String localName = plugin.getLocalPlayerName();
		boolean isOwn = localName != null && localName.equals(listing.getPlayerName());

		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(isOwn ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, isOwn ? 110 : 105));

		Color bgColor = isOwn ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR;

		// Info section
		JPanel infoPanel = new JPanel(new GridLayout(0, 1, 0, 2));
		infoPanel.setBackground(bgColor);

		JLabel activityLabel = new JLabel(listing.getActivity().getDisplayName());
		activityLabel.setForeground(Color.WHITE);
		infoPanel.add(activityLabel);

		if (isOwn)
		{
			JLabel ownerLabel = new JLabel(listing.getPlayerName() + " (You)");
			ownerLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			infoPanel.add(ownerLabel);

			// Editable party size row: [−] 1/4 [+]
			JPanel sizePanel = new JPanel(new BorderLayout());
			sizePanel.setBackground(bgColor);

			JButton minusButton = new JButton("−");
			minusButton.setMargin(new java.awt.Insets(0, 4, 0, 4));
			minusButton.setToolTipText("Remove a member");
			minusButton.setEnabled(listing.getCurrentSize() > 1);
			minusButton.addActionListener(e ->
				plugin.updateGroupSize(listing.getId(), listing.getCurrentSize() - 1));

			JLabel sizeLabel = new JLabel(" " + listing.getCurrentSize() + "/" + listing.getMaxSize() + " ");
			sizeLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);

			JButton plusButton = new JButton("+");
			plusButton.setMargin(new java.awt.Insets(0, 4, 0, 4));
			plusButton.setToolTipText("Add a member");
			plusButton.setEnabled(listing.getCurrentSize() < listing.getMaxSize());
			plusButton.addActionListener(e ->
				plugin.updateGroupSize(listing.getId(), listing.getCurrentSize() + 1));

			JPanel sizeControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			sizeControls.setBackground(bgColor);
			sizeControls.add(minusButton);
			sizeControls.add(sizeLabel);
			sizeControls.add(plusButton);
			sizePanel.add(sizeControls, BorderLayout.WEST);

			infoPanel.add(sizePanel);
		}
		else
		{
			JLabel playerLabel = new JLabel(listing.getPlayerName() + " (" + listing.getCurrentSize() + "/" + listing.getMaxSize() + ")");
			playerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			infoPanel.add(playerLabel);
		}

		if (listing.getDescription() != null && !listing.getDescription().isEmpty())
		{
			String desc = listing.getDescription();
			if (desc.length() > 40)
			{
				desc = desc.substring(0, 37) + "...";
			}
			JLabel descLabel = new JLabel(desc);
			descLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			infoPanel.add(descLabel);
		}

		card.add(infoPanel, BorderLayout.CENTER);

		// Buttons panel
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
		buttonPanel.setBackground(bgColor);

		if (isOwn)
		{
			// Delete icon for own listings
			JLabel deleteLabel = new JLabel(DELETE_ICON);
			deleteLabel.setToolTipText("Delete Group");
			deleteLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			deleteLabel.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					plugin.deleteGroup(listing.getId());
				}
			});
			buttonPanel.add(deleteLabel);
		}
		else
		{
			// Copy name button for others' listings
			JButton copyButton = new JButton("Copy Name");
			copyButton.setPreferredSize(new Dimension(90, 25));
			copyButton.setMaximumSize(new Dimension(90, 25));
			copyButton.addActionListener(e ->
			{
				StringSelection selection = new StringSelection(listing.getPlayerName());
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
				copyButton.setText("Copied!");
				SwingUtilities.invokeLater(() ->
				{
					try
					{
						Thread.sleep(1500);
					}
					catch (InterruptedException ignored)
					{
					}
					copyButton.setText("Copy Name");
				});
			});
			buttonPanel.add(copyButton);

			JButton joinFcButton = new JButton("Join FC");
			joinFcButton.setPreferredSize(new Dimension(90, 25));
			joinFcButton.setMaximumSize(new Dimension(90, 25));
			String fcName = listing.getFriendsChatName();
			joinFcButton.setToolTipText(fcName != null && !fcName.isEmpty()
				? "Join " + fcName + "'s Friends Chat"
				: "No Friends Chat name available");
			joinFcButton.setEnabled(fcName != null && !fcName.isEmpty());
			joinFcButton.addActionListener(e -> plugin.joinFriendsChat(fcName));
			buttonPanel.add(joinFcButton);
		}

		card.add(buttonPanel, BorderLayout.EAST);

		return card;
	}

	private void showCreateDialog()
	{
		JLabel fcWarningLabel = new JLabel("⚠ You are not in a Friends Chat. Join one first!");
		fcWarningLabel.setForeground(new Color(255, 180, 0));
		fcWarningLabel.setVisible(!plugin.isInFriendsChat());

		plugin.setFcStatusCallback(() -> fcWarningLabel.setVisible(!plugin.isInFriendsChat()));

		JPanel formPanel = new JPanel(new GridLayout(4, 2, 5, 5));

		formPanel.add(new JLabel("Activity:"));
		JComboBox<Activity> activityBox = new JComboBox<>(Activity.values());
		formPanel.add(activityBox);

		formPanel.add(new JLabel("Max Size:"));
		JSpinner maxSizeSpinner = new JSpinner(new SpinnerNumberModel(4, 2, 100, 1));
		formPanel.add(maxSizeSpinner);

		formPanel.add(new JLabel("Current Size:"));
		JSpinner currentSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
		formPanel.add(currentSizeSpinner);

		formPanel.add(new JLabel("Description:"));
		JTextField descField = new JTextField();
		formPanel.add(descField);

		JPanel wrapperPanel = new JPanel();
		wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.Y_AXIS));
		wrapperPanel.add(fcWarningLabel);
		wrapperPanel.add(formPanel);

		int result = JOptionPane.showConfirmDialog(
			this,
			wrapperPanel,
			"Create Group",
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE
		);

		plugin.setFcStatusCallback(null);

		if (result == JOptionPane.OK_OPTION)
		{
			GroupListing listing = new GroupListing();
			listing.setActivity((Activity) activityBox.getSelectedItem());
			listing.setMaxSize((int) maxSizeSpinner.getValue());
			listing.setCurrentSize((int) currentSizeSpinner.getValue());
			listing.setDescription(descField.getText().trim());

			plugin.createGroup(listing);
		}
	}
}
