package com.ironmanhelper;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Zijpaneel: een knop "Sync now" (profiel meteen versturen, en de bank als die open is of net was) en per soort
 * data wanneer er voor het laatst gesynct is en of dat gelukt is.
 */
class IronmanHelperSyncPanel extends PluginPanel
{
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final JButton syncButton = new JButton("Sync now");
	private final JLabel bankStatus = status();
	private final JLabel profileStatus = status();
	private final JLabel liveStatus = status();

	IronmanHelperSyncPanel(Runnable onSync)
	{
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Ironman Helper Sync");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(title);

		JLabel hint = new JLabel("<html>Syncs to ironman-helper.com. Choose what to share in the plugin settings.</html>");
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setBorder(BorderFactory.createEmptyBorder(4, 0, 10, 0));
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(hint);

		syncButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		syncButton.setFocusPainted(false);
		syncButton.addActionListener(e -> onSync.run());
		content.add(syncButton);

		JPanel rows = new JPanel(new GridLayout(0, 1, 0, 4));
		rows.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		rows.setAlignmentX(Component.LEFT_ALIGNMENT);
		rows.add(row("Bank", bankStatus));
		rows.add(row("Stats & items", profileStatus));
		rows.add(row("Live XP", liveStatus));
		content.add(rows);

		add(content, BorderLayout.NORTH);
	}

	private static JLabel status()
	{
		JLabel l = new JLabel("not synced yet");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private static JPanel row(String name, JLabel value)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JLabel n = new JLabel(name);
		n.setForeground(ColorScheme.TEXT_COLOR);
		p.add(n, BorderLayout.NORTH);
		p.add(value, BorderLayout.SOUTH);
		return p;
	}

	enum Kind
	{
		BANK, PROFILE, LIVE
	}

	/** Status bijwerken (mag vanaf elke thread). */
	void setStatus(Kind kind, boolean ok, String text)
	{
		String line = (ok ? "✓ " : "✗ ") + text + " (" + LocalTime.now().format(TIME) + ")";
		SwingUtilities.invokeLater(() ->
		{
			JLabel l = kind == Kind.BANK ? bankStatus : kind == Kind.PROFILE ? profileStatus : liveStatus;
			l.setText(line);
			l.setForeground(ok ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
		});
	}

	void setSyncing(boolean busy)
	{
		SwingUtilities.invokeLater(() ->
		{
			syncButton.setEnabled(!busy);
			syncButton.setText(busy ? "Syncing…" : "Sync now");
		});
	}
}
