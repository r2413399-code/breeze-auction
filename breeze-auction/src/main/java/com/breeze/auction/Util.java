package com.breeze.auction;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Util {
	private static final Pattern DURATION = Pattern.compile("^(\\d{1,7})([smhd]?)$");

	private Util() {
	}

	/** "30" = 30 minutes, "90s", "45m", "2h", "3d". Returns -1 if it cannot be read. */
	public static long parseDuration(String text) {
		Matcher m = DURATION.matcher(text.toLowerCase(Locale.ROOT));
		if (!m.matches()) {
			return -1;
		}
		long n = Long.parseLong(m.group(1));
		return switch (m.group(2)) {
			case "s" -> n * 1000L;
			case "h" -> n * 3_600_000L;
			case "d" -> n * 86_400_000L;
			default -> n * 60_000L;
		};
	}

	public static String duration(long ms) {
		long s = Math.max(0, ms / 1000);
		long d = s / 86400;
		long h = (s % 86400) / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		StringBuilder sb = new StringBuilder();
		if (d > 0) sb.append(d).append("d ");
		if (h > 0) sb.append(h).append("h ");
		if (m > 0) sb.append(m).append("m ");
		if (d == 0 && h == 0) sb.append(sec).append("s");
		return sb.toString().trim();
	}
}
