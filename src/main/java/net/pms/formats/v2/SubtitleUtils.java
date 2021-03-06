/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2012  I. Sokolov
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.formats.v2;

import java.io.*;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.io.OutputParams;
import static net.pms.util.StringUtil.*;
import static org.apache.commons.io.FilenameUtils.getBaseName;
import static org.apache.commons.lang3.StringUtils.*;
import static org.mozilla.universalchardet.Constants.*;

public class SubtitleUtils {
	private final static PmsConfiguration configuration = PMS.getConfiguration();
	private final static Map<String, String> fileCharsetToMencoderSubcpOptionMap = new HashMap<String, String>() {
		private static final long serialVersionUID = 1L;

		{
			// Cyrillic / Russian
			put(CHARSET_IBM855, "enca:ru:cp1251");
			put(CHARSET_ISO_8859_5, "enca:ru:cp1251");
			put(CHARSET_KOI8_R, "enca:ru:cp1251");
			put(CHARSET_MACCYRILLIC, "enca:ru:cp1251");
			put(CHARSET_WINDOWS_1251, "enca:ru:cp1251");
			put(CHARSET_IBM866, "enca:ru:cp1251");
			// Greek
			put(CHARSET_WINDOWS_1253, "cp1253");
			put(CHARSET_ISO_8859_7, "ISO-8859-7");
			// Western Europe
			put(CHARSET_WINDOWS_1252, "cp1252");
			// Hebrew
			put(CHARSET_WINDOWS_1255, "cp1255");
			put(CHARSET_ISO_8859_8, "ISO-8859-8");
			// Chinese
			put(CHARSET_ISO_2022_CN, "ISO-2022-CN");
			put(CHARSET_BIG5, "enca:zh:big5");
			put(CHARSET_GB18030, "enca:zh:big5");
			put(CHARSET_EUC_TW, "enca:zh:big5");
			put(CHARSET_HZ_GB_2312, "enca:zh:big5");
			// Korean
			put(CHARSET_ISO_2022_KR, "cp949");
			put(CHARSET_EUC_KR, "euc-kr");
			// Japanese
			put(CHARSET_ISO_2022_JP, "ISO-2022-JP");
			put(CHARSET_EUC_JP, "euc-jp");
			put(CHARSET_SHIFT_JIS, "shift-jis");
		}
	};

	/**
	 * Returns value for -subcp option for non UTF-8 external subtitles based on
	 * detected charset.
	 *
	 * @param dlnaMediaSubtitle DLNAMediaSubtitle with external subtitles file.
	 * @return value for mencoder's -subcp option or null if can't determine.
	 */
	public static String getSubCpOptionForMencoder(DLNAMediaSubtitle dlnaMediaSubtitle) {
		if (dlnaMediaSubtitle == null) {
			throw new NullPointerException("dlnaMediaSubtitle can't be null.");
		}
		if (isBlank(dlnaMediaSubtitle.getExternalFileCharacterSet())) {
			return null;
		}
		return fileCharsetToMencoderSubcpOptionMap.get(dlnaMediaSubtitle.getExternalFileCharacterSet());
	}

	/**
	 * Applies codepage conversion and timeseeking to subtitles file in ASS/SSA and SUBRIP format if needed 
	 *
	 * @param subsFile Subtitles file
	 * @param params Output parameters with time stamp value
	 * @return Converted subtitles file
	 * @throws IOException
	 */
	public static File applyCodepageConversionAndTimeseekingToSubtitlesFile(OutputParams params) throws IOException {
		Double timeseek = params.timeseek;
		Double startTime;
		Double endTime;
		String line;
		BufferedReader reader;
		File outputSubs;
		String cp = configuration.getSubtitlesCodepage();
		String subsFileCharset = params.sid.getExternalFileCharacterSet();
		File subsFile = params.sid.getExternalFile();
		outputSubs = new File(configuration.getTempFolder(), getBaseName(subsFile.getName()) + "_" + System.currentTimeMillis()  + ".tmp");
		final boolean isSubtitlesCodepageForcedInConfigurationAndSupportedByJVM = isNotBlank(cp) && Charset.isSupported(cp) && !params.sid.isExternalFileUtf();
		final boolean isSubtitlesCodepageAutoDetectedAndSupportedByJVM = isNotBlank(subsFileCharset) && Charset.isSupported(subsFileCharset);
		if (isSubtitlesCodepageForcedInConfigurationAndSupportedByJVM) {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(subsFile), Charset.forName(cp)));
		} else if (isSubtitlesCodepageAutoDetectedAndSupportedByJVM) {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(subsFile), Charset.forName(subsFileCharset)));
		} else {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(subsFile)));
		}

		if (params.sid.getType() == SubtitleType.ASS) {
			try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputSubs), Charset.forName(CHARSET_UTF_8)))) {

				while ((line = reader.readLine()) != null) {
					if (line.startsWith("Dialogue:")) {
						String[] tempStr = line.split(",");
						startTime = convertStringToTime(tempStr[1]);
						endTime = convertStringToTime(tempStr[2]);

						if (startTime >= timeseek) {
							tempStr[1] = convertTimeToString(startTime - timeseek, ASS_TIME_FORMAT);
							tempStr[2] = convertTimeToString(endTime - timeseek, ASS_TIME_FORMAT);
						} else {
							continue;
						}

						output.write(join(tempStr, ",") + "\n");
					} else {
						output.write(line + "\n");
					}
				}

				output.flush();
				output.close();
			}
		} else if (params.sid.getType() == SubtitleType.SUBRIP) {
			try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputSubs), Charset.forName(CHARSET_UTF_8)))) {
				int n = 1;

				while ((line = reader.readLine()) != null) {
					if (line.contains("-->")) {
						String start = line.substring(0, line.indexOf("-->") - 1);
						String end = line.substring(line.indexOf("-->") + 4);
						startTime = convertStringToTime(start);
						endTime = convertStringToTime(end);

						if (startTime >= timeseek) {
							output.write("" + (n++) + "\n");
							output.write(convertTimeToString(startTime - timeseek, SRT_TIME_FORMAT));
							output.write(" --> ");
							output.write(convertTimeToString(endTime - timeseek, SRT_TIME_FORMAT) + "\n");

							while (isNotBlank(line = reader.readLine())) { // Read all following subs lines
								output.write(line + "\n");
							}

							output.write("" + "\n");
						}
					}
				}

				output.flush();
				output.close();
			}
		} else {
			reader.close();
			return null;
		}

		reader.close();
		outputSubs.deleteOnExit();
		return outputSubs;
	}
}
