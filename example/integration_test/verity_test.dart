import 'dart:io';

import 'package:integration_test/integration_test.dart';
import 'package:polar/polar.dart';

import '../../test/tests.dart';

const identifier = 'D7C70D2C';

void main() async {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  await requestPermissions();
  testSearch(identifier);
  testConnection(identifier);
  testBasicData(identifier);
  testBleSdkFeatures(
    identifier,
    features: PolarSdkFeature.values.toSet().difference({
      PolarSdkFeature.h10ExerciseRecording,
      PolarSdkFeature.activityData,
      if (Platform.isIOS) PolarSdkFeature.fileTransfer,
      PolarSdkFeature.hts,
      PolarSdkFeature.sleepData,
      PolarSdkFeature.temperatureData,
    }),
  );
  testStreaming(
    identifier,
    features: {
      PolarDataType.hr,
      PolarDataType.acc,
      PolarDataType.ppg,
      PolarDataType.ppi,
      PolarDataType.gyro,
      PolarDataType.magnetometer,
    },
  );
  testOfflineRecordingList(identifier);
  testOfflineRecordingFetch(identifier);
  testSdkMode(identifier);
  testMisc(identifier, supportsLedConfig: true);
}
