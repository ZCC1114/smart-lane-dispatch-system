package com.smartlane.dispatch.device.parking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParkingMfMessageParserTests {

	private final ParkingMfMessageParser parser = new ParkingMfMessageParser();

	@Test
	void parseHeartbeat() {
		String topic = "/00E02721A3A7/mf/up";
		String payload = """
				{"cmd":"heartbeat","data":{"deviceStatus":[{"checkTime":"2026-05-12 14:53:12","deviceNo":"22K5000202407828","groupId":"90HZNII","network":"online"}],"ip":"192.168.0.30","runtime":{"cpu":{"load":"0.59,0.73,0.65","logicalProcessorCount":4,"name":"Intel(R) Celeron(R) CPU J1900 @ 1.99GHz","physicalProcessorCount":4},"disk":{"free":46.83,"total":54.52},"memory":{"free":1.90,"total":3.60},"os":{"osName":"Core","osVersion":"7.9.2009"}},"version":"0.0.1"},"msgId":"C_6a02ce5a7f3eb46e4e2dda0d","sn":"00E02721A3A7","timestamp":1778568794575,"timezone":"Asia/Shanghai"}
				""";

		ParkingMfMessage msg = parser.parse(topic, payload);

		assertThat(msg).isNotNull();
		assertThat(msg.isHeartbeat()).isTrue();
		assertThat(msg.isPlateResult()).isFalse();
		assertThat(msg.sn()).isEqualTo("00E02721A3A7");
		assertThat(msg.msgId()).isEqualTo("C_6a02ce5a7f3eb46e4e2dda0d");
		assertThat(msg.timestamp()).isEqualTo(1778568794575L);
		assertThat(msg.timezone()).isEqualTo("Asia/Shanghai");

		ParkingMfMessage.HeartbeatData data = msg.heartbeatData();
		assertThat(data).isNotNull();
		assertThat(data.ip()).isEqualTo("192.168.0.30");
		assertThat(data.version()).isEqualTo("0.0.1");
		assertThat(data.deviceStatus()).hasSize(1);

		ParkingMfMessage.DeviceStatus status = data.deviceStatus().get(0);
		assertThat(status.deviceNo()).isEqualTo("22K5000202407828");
		assertThat(status.groupId()).isEqualTo("90HZNII");
		assertThat(status.network()).isEqualTo("online");

		ParkingMfMessage.RuntimeInfo runtime = data.runtime();
		assertThat(runtime.cpu().name()).contains("J1900");
		assertThat(runtime.disk().free()).isEqualTo(46.83);
		assertThat(runtime.memory().total()).isEqualTo(3.60);
		assertThat(runtime.os().osName()).isEqualTo("Core");
	}

	@Test
	void parsePlateResult() {
		String topic = "/00E02721A3A7/mf/up";
		String payload = """
				{"cmd":"plateResult","data":{"carBrand":"","carImg":"https://img.bolinkpay.com/picture/00E02721A3A7/22K5000202407828/20260512/14/20260512_145336_000.jpg","confidence":27,"deviceNo":"22K5000202407828","groupId":"90HZNII","parkingTime":"2026-05-12 14:53:36","plateColor":"BLUE","plateNo":"苏B3R89T","realTime":true,"uploadTime":1778568817063},"msgId":"C_6a02ce717f3eb46e4e2dda0f","sn":"00E02721A3A7","timestamp":1778568817063,"timezone":"Asia/Shanghai"}
				""";

		ParkingMfMessage msg = parser.parse(topic, payload);

		assertThat(msg).isNotNull();
		assertThat(msg.isHeartbeat()).isFalse();
		assertThat(msg.isPlateResult()).isTrue();
		assertThat(msg.sn()).isEqualTo("00E02721A3A7");

		ParkingMfMessage.PlateResultData data = msg.plateResultData();
		assertThat(data).isNotNull();
		assertThat(data.plateNo()).isEqualTo("苏B3R89T");
		assertThat(data.plateColor()).isEqualTo("BLUE");
		assertThat(data.confidence()).isEqualTo(27);
		assertThat(data.carImg()).startsWith("https://");
		assertThat(data.parkingTime()).isEqualTo("2026-05-12 14:53:36");
		assertThat(data.realTime()).isTrue();
	}

	@Test
	void extractSnFromTopic_ok() {
		assertThat(ParkingMfMessageParser.extractSnFromTopic("/00E02721A3A7/mf/up")).isEqualTo("00E02721A3A7");
		assertThat(ParkingMfMessageParser.extractSnFromTopic("/ABC123/mf/up")).isEqualTo("ABC123");
	}

	@Test
	void extractSnFromTopic_null() {
		assertThat(ParkingMfMessageParser.extractSnFromTopic(null)).isNull();
		assertThat(ParkingMfMessageParser.extractSnFromTopic("/device/123/update")).isNull();
	}

	@Test
	void isMfUpTopic() {
		assertThat(ParkingMfMessageParser.isMfUpTopic("/00E02721A3A7/mf/up")).isTrue();
		assertThat(ParkingMfMessageParser.isMfUpTopic("/device/123/update")).isFalse();
	}

	@Test
	void parseInvalidPayload_returnsNull() {
		assertThat(parser.parse("/00E02721A3A7/mf/up", "not-json")).isNull();
		assertThat(parser.parse("/00E02721A3A7/mf/up", "")).isNull();
		assertThat(parser.parse("/00E02721A3A7/mf/up", (String) null)).isNull();
	}
}
