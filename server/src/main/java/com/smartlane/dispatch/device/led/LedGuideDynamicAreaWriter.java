package com.smartlane.dispatch.device.led;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

@Service
public class LedGuideDynamicAreaWriter {

	private static final int DEFAULT_SCREEN_WIDTH = 1920;
	private static final int DEFAULT_SCREEN_HEIGHT = 960;

	private final LedGuideDisplayProperties properties;
	private final LedGuideDynamicAreaClient client;
	private final Object monitor = new Object();
	private final String[] lastRowKeys = new String[LedGuideDisplayFrame.ROW_COUNT];

	private long lastConnectionVersion = -1;

	public LedGuideDynamicAreaWriter(
			LedGuideDisplayProperties properties,
			LedGuideDynamicAreaClient client) {
		this.properties = properties;
		this.client = client;
	}

	public LedGuideDisplayWriteResult write(LedGuideDisplayFrame frame, boolean forceRefresh) {
		if (!"6".equals(properties.getGeneration())) {
			return LedGuideDisplayWriteResult.failure("动态区写入仅支持六代控制卡");
		}

		synchronized (monitor) {
			long currentConnectionVersion = client.connectionVersion();
			if (currentConnectionVersion != lastConnectionVersion) {
				Arrays.fill(lastRowKeys, null);
				lastConnectionVersion = currentConnectionVersion;
			}

			try {
				List<LedGuideDynamicAreaRequest> requests = buildRequests(frame);
				int sentCount = 0;
				for (int index = 0; index < requests.size(); index++) {
					LedGuideDynamicAreaRequest request = requests.get(index);
					String cacheKey = request.cacheKey();
					if (forceRefresh || !cacheKey.equals(lastRowKeys[index])) {
						client.write(request);
						lastRowKeys[index] = cacheKey;
						sentCount++;
					}
				}
				lastConnectionVersion = client.connectionVersion();
				return LedGuideDisplayWriteResult.success("动态区发送成功，更新 " + sentCount + " 行");
			} catch (Exception ex) {
				Arrays.fill(lastRowKeys, null);
				return LedGuideDisplayWriteResult.failure("动态区发送失败: " + ex.getMessage());
			}
		}
	}

	List<LedGuideDynamicAreaRequest> buildRequests(LedGuideDisplayFrame frame) {
		int screenWidth = positive(properties.getScreenWidth(), DEFAULT_SCREEN_WIDTH);
		int screenHeight = positive(properties.getScreenHeight(), DEFAULT_SCREEN_HEIGHT);
		int rowHeight = Math.max(1, screenHeight / LedGuideDisplayFrame.ROW_COUNT);
		int areaStartId = Math.max(0, properties.getDynamicAreaStartId());
		return IntStream.range(0, frame.rows().size())
				.mapToObj(index -> {
					LedGuideDisplayFrame.Line row = frame.rows().get(index);
					int y = index * rowHeight;
					int height = index == LedGuideDisplayFrame.ROW_COUNT - 1 ? screenHeight - y : rowHeight;
					return new LedGuideDynamicAreaRequest(
							properties.getIp(),
							properties.getPort(),
							properties.getModel(),
							screenWidth,
							screenHeight,
							areaStartId + index,
							0,
							y,
							screenWidth,
							Math.max(1, height),
							row.text(),
							row.fontSize(),
							row.color());
				})
				.toList();
	}

	private int positive(int value, int defaultValue) {
		return value > 0 ? value : defaultValue;
	}
}
