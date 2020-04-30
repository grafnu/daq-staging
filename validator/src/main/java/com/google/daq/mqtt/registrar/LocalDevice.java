package com.google.daq.mqtt.registrar;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.ISO8601DateFormat;
import com.google.api.services.cloudiot.v1.model.DeviceCredential;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.daq.mqtt.util.CloudDeviceSettings;
import com.google.daq.mqtt.util.CloudIotManager;
import org.apache.commons.io.IOUtils;
import org.everit.json.schema.Schema;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

import static com.google.daq.mqtt.registrar.Registrar.*;

public class LocalDevice {

  private static final PrettyPrinter PROPER_PRETTY_PRINTER_POLICY = new ProperPrettyPrinterPolicy();

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .enable(Feature.ALLOW_TRAILING_COMMA)
      .enable(Feature.STRICT_DUPLICATE_DETECTION)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setDateFormat(new ISO8601DateFormat())
      .setSerializationInclusion(Include.NON_NULL);

  private static final String RSA_PUBLIC_PEM = "rsa_public.pem";
  private static final String RSA_PRIVATE_PEM = "rsa_private.pem";
  private static final String RSA_PRIVATE_PKCS8 = "rsa_private.pkcs8";
  private static final String PHYSICAL_TAG_FORMAT = "%s_%s";
  private static final String PHYSICAL_TAG_ERROR = "Physical asset name %s does not match expected %s";

  private static final Set<String> DEVICE_FILES = ImmutableSet.of(METADATA_JSON);
  private static final Set<String> KEY_FILES = ImmutableSet.of(RSA_PUBLIC_PEM, RSA_PRIVATE_PEM, RSA_PRIVATE_PKCS8);
  private static final Set<String> OPTIONAL_FILES = ImmutableSet.of(GENERATED_CONFIG_JSON);
  private static final String KEYGEN_EXEC_FORMAT = "validator/bin/keygen %s %s";
  public static final String METADATA_SUBFOLDER = "metadata";

  private final String deviceId;
  private final Map<String, Schema> schemas;
  private final File deviceDir;
  private final UdmiSchema.Metadata metadata;
  private final File devicesDir;

  private String deviceNumId;

  private CloudDeviceSettings settings;

  LocalDevice(File devicesDir, String deviceId, Map<String, Schema> schemas) {
    try {
      this.deviceId = deviceId;
      this.schemas = schemas;
      this.devicesDir = devicesDir;
      deviceDir = new File(devicesDir, deviceId);
      metadata = readMetadata();
    } catch (Exception e) {
      throw new RuntimeException("While loading local device " + deviceId, e);
    }
  }

  static boolean deviceExists(File devicesDir, String deviceName) {
    return new File(new File(devicesDir, deviceName), METADATA_JSON).isFile();
  }

  public void validatedDeviceDir() {
    try {
      String[] files = deviceDir.list();
      Preconditions.checkNotNull(files, "No files found in " + deviceDir.getAbsolutePath());
      ImmutableSet<String> actualFiles = ImmutableSet.copyOf(files);
      Set<String> expectedFiles = isDirectConnect() ? Sets.union(KEY_FILES, DEVICE_FILES) : DEVICE_FILES;
      SetView<String> missing = Sets.difference(expectedFiles, actualFiles);
      if (!missing.isEmpty()) {
        throw new RuntimeException("Missing files: " + missing);
      }
      SetView<String> extra = Sets.difference(Sets.difference(actualFiles, expectedFiles), OPTIONAL_FILES);
      if (!extra.isEmpty()) {
        throw new RuntimeException("Extra files: " + extra);
      }
    } catch (Exception e) {
      throw new RuntimeException("While validating device directory " + deviceId, e);
    }
  }

  private UdmiSchema.Metadata readMetadata() {
    File metadataFile = new File(deviceDir, METADATA_JSON);
    try (InputStream targetStream = new FileInputStream(metadataFile)) {
      schemas.get(METADATA_JSON).validate(new JSONObject(new JSONTokener(targetStream)));
    } catch (Exception e1) {
      throw new RuntimeException("Processing input " + metadataFile, e1);
    }
    try {
      return OBJECT_MAPPER.readValue(metadataFile, UdmiSchema.Metadata.class);
    } catch (Exception e) {
      throw new RuntimeException("While reading "+ metadataFile.getAbsolutePath(), e);
    }
  }

  private String metadataHash() {
    try {
      String savedHash = metadata.hash;
      metadata.hash = null;
      String json = metadataString();
      metadata.hash = savedHash;
      return String.format("%08x", Objects.hash(json));
    } catch (Exception e) {
      throw new RuntimeException("Converting object to string", e);
    }
  }

  private String getAuthType() {
    return metadata.cloud == null ? null : metadata.cloud.auth_type;
  }

  private DeviceCredential loadCredential() {
    try {
      if (hasGateway() && getAuthType() != null) {
        throw new RuntimeException("Proxied devices should not have auth_type defined");
      }
      if (!isDirectConnect()) {
        return null;
      }
      if (getAuthType() == null) {
        throw new RuntimeException("Credential auth_type definition missing");
      }
      File deviceKeyFile = new File(deviceDir, RSA_PUBLIC_PEM);
      if (!deviceKeyFile.exists()) {
        generateNewKey();
      }
      return CloudIotManager.makeCredentials(getAuthType(),
          IOUtils.toString(new FileInputStream(deviceKeyFile), Charset.defaultCharset()));
    } catch (Exception e) {
      throw new RuntimeException("While loading credential for local device " + deviceId, e);
    }
  }

  private void generateNewKey() {
    String absolutePath = deviceDir.getAbsolutePath();
    try {
      String command = String.format(KEYGEN_EXEC_FORMAT, metadata.cloud.auth_type, absolutePath);
      System.err.println(command);
      int exitCode = Runtime.getRuntime().exec(command).waitFor();
      if (exitCode != 0) {
        throw new RuntimeException("Keygen exit code " + exitCode);
      }
    } catch (Exception e) {
      throw new RuntimeException("While generating new credential for " + deviceId, e);
    }
  }

  boolean isGateway() {
    return metadata.gateway != null &&
        metadata.gateway.proxy_ids != null;
  }

  boolean hasGateway() {
    return metadata.gateway != null &&
        metadata.gateway.gateway_id != null;
  }

  boolean isDirectConnect() {
    return isGateway() || !hasGateway();
  }

  String getGatewayId() {
    return hasGateway() ? metadata.gateway.gateway_id : null;
  }

  CloudDeviceSettings getSettings() {
    try {
      if (settings != null) {
        return settings;
      }

      settings = new CloudDeviceSettings();
      settings.credential = loadCredential();
      settings.metadata = metadataString();
      settings.config = deviceConfigString();
      settings.proxyDevices = getProxyDevicesList();
      return settings;
    } catch (Exception e) {
      throw new RuntimeException("While getting settings for device " + deviceId, e);
    }
  }

  private List<String> getProxyDevicesList() {
    return isGateway() ? metadata.gateway.proxy_ids : null;
  }

  private String deviceConfigString() {
    try {
      UdmiSchema.Config config = new UdmiSchema.Config();
      config.timestamp = metadata.timestamp;
      if (isGateway()) {
        config.gateway = new UdmiSchema.GatewayConfig();
        config.gateway.proxy_ids = getProxyDevicesList();
      }
      if (metadata.pointset != null) {
        config.pointset = getDevicePointsetConfig();
      }
      if (metadata.localnet != null) {
        config.localnet = getDeviceLocalnetConfig();
      }
      return OBJECT_MAPPER.writeValueAsString(config);
    } catch (Exception e) {
      throw new RuntimeException("While converting device config to string", e);
    }
  }

  private UdmiSchema.LocalnetConfig getDeviceLocalnetConfig() {
    UdmiSchema.LocalnetConfig localnetConfig = new UdmiSchema.LocalnetConfig();
    localnetConfig.subsystems = metadata.localnet.subsystem;
    return localnetConfig;
  }

  private UdmiSchema.PointsetConfig getDevicePointsetConfig() {
    UdmiSchema.PointsetConfig pointsetConfig = new UdmiSchema.PointsetConfig();
    metadata.pointset.points.forEach((metadataKey, value) ->
        pointsetConfig.points.computeIfAbsent(metadataKey, configKey ->
            UdmiSchema.PointConfig.fromRef(value.ref)));
    return pointsetConfig;
  }

  private String metadataString() {
    try {
      return OBJECT_MAPPER.writeValueAsString(metadata);
    } catch (Exception e) {
      throw new RuntimeException("While converting metadata to string", e);
    }
  }

  public void validate(String registryId, String siteName) {
    try {
      UdmiSchema.Envelope envelope = new UdmiSchema.Envelope();
      envelope.deviceId = deviceId;
      envelope.deviceRegistryId = registryId;
      // Don't use actual project id because it should be abstracted away.
      envelope.projectId = fakeProjectId();
      envelope.deviceNumId = makeNumId(envelope);
      String envelopeJson = OBJECT_MAPPER.writeValueAsString(envelope);
      schemas.get(ENVELOPE_JSON).validate(new JSONObject(new JSONTokener(envelopeJson)));
    } catch (Exception e) {
      throw new IllegalStateException("Validating envelope " + deviceId, e);
    }
    checkConsistency(siteName);
  }

  private String fakeProjectId() {
    return metadata.system.location.site_name.toLowerCase();
  }

  private void checkConsistency(String expected_site_name) {
    String siteName = metadata.system.location.site_name;
    String desiredTag = String.format(PHYSICAL_TAG_FORMAT, siteName, deviceId);
    String assetName = metadata.system.physical_tag.asset.name;
    Preconditions.checkState(desiredTag.equals(assetName),
        String.format(PHYSICAL_TAG_ERROR, assetName, desiredTag));
    String errorMessage = "Site name " + siteName + " is not expected " + expected_site_name;
    Preconditions.checkState(expected_site_name.equals(siteName), errorMessage);
  }

  private String makeNumId(UdmiSchema.Envelope envelope) {
    int hash = Objects.hash(deviceId, envelope.deviceRegistryId, envelope.projectId);
    return Integer.toString(hash < 0 ? -hash : hash);
  }

  void writeNormalized() {
    File metadataFile = new File(deviceDir, METADATA_JSON);
    try (OutputStream outputStream = new FileOutputStream(metadataFile)) {
      String writeHash = metadataHash();
      boolean update = metadata.hash == null || !metadata.hash.equals(writeHash);
      if (update) {
        metadata.timestamp = new Date();
        metadata.hash = metadataHash();
      }
      // Super annoying, but can't set this on the global static instance.
      JsonGenerator generator = OBJECT_MAPPER.getFactory()
          .createGenerator(outputStream)
          .setPrettyPrinter(PROPER_PRETTY_PRINTER_POLICY);
      OBJECT_MAPPER.writeValue(generator, metadata);
    } catch (Exception e) {
      throw new RuntimeException("While writing "+ metadataFile.getAbsolutePath(), e);
    }
  }

  public void writeConfigFile() {
    File configFile = new File(deviceDir, GENERATED_CONFIG_JSON);
    try (OutputStream outputStream = new FileOutputStream(configFile)) {
      outputStream.write(settings.config.getBytes());
    } catch (Exception e) {
      throw new RuntimeException("While writing "+ configFile.getAbsolutePath(), e);
    }
  }

  public String getDeviceId() {
    return deviceId;
  }

  public String getDeviceNumId() {
    return Preconditions.checkNotNull(deviceNumId, "deviceNumId not set");
  }

  public void setDeviceNumId(String numId) {
    deviceNumId = numId;
  }

  private static class ProperPrettyPrinterPolicy extends DefaultPrettyPrinter {
    @Override
    public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException {
      jg.writeRaw(": ");
    }
  }
}
