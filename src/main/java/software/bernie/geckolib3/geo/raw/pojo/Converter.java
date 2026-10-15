// To use this code, add the following Maven dependency to your project:
//
//
// com.fasterxml.jackson.core : jackson-databind : 2.9.0
// com.fasterxml.jackson.datatype : jackson-datatype-jsr310 : 2.9.0
//
// Import this package:
//
// import com.fox.ysmu.geckolib.file.geo.Converter;
//
// Then you can deserialize a JSON string with
//
// GeoModel data = Converter.fromJsonString(jsonString);

package software.bernie.geckolib3.geo.raw.pojo;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

public class Converter {
    // Date-time helpers

    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendOptional(DateTimeFormatter.ISO_DATE_TIME)
        .appendOptional(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        .appendOptional(DateTimeFormatter.ISO_INSTANT)
        .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SX"))
        .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssX"))
        .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .toFormatter()
        .withZone(ZoneOffset.UTC);

    public static OffsetDateTime parseDateTimeString(String str) {
        return ZonedDateTime.from(Converter.DATE_TIME_FORMATTER.parse(str))
            .toOffsetDateTime();
    }

    private static final DateTimeFormatter TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendOptional(DateTimeFormatter.ISO_TIME)
        .appendOptional(DateTimeFormatter.ISO_OFFSET_TIME)
        .parseDefaulting(ChronoField.YEAR, 2020)
        .parseDefaulting(ChronoField.MONTH_OF_YEAR, 1)
        .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
        .toFormatter()
        .withZone(ZoneOffset.UTC);

    public static OffsetTime parseTimeString(String str) {
        return ZonedDateTime.from(Converter.TIME_FORMATTER.parse(str))
            .toOffsetDateTime()
            .toOffsetTime();
    }
    // Serialize/deserialize helpers

    public static RawGeoModel fromJsonString(String json) throws IOException {
        return Mapper.READER.readValue(json);
    }

    public static String toJsonString(RawGeoModel obj) throws JsonProcessingException {
        return Mapper.WRITER.writeValueAsString(obj);
    }

    /**
     * The shared Jackson reader and writer, built on first use.
     * <p>
     * A holder class rather than a pair of lazily assigned statics: class initialisation is guaranteed by the JVM to
     * run once, under a lock, and to publish the result safely, so two threads reaching this at the same time cannot
     * build two mappers - or observe a half-constructed one. That matters now that geometry is parsed on a pool:
     * chunk-loading used to be the only caller and it is single-threaded, so the previous check-then-act on
     * non-volatile statics was never exercised. It stays lazy, because the holder is only initialised when
     * {@link Converter#fromJsonString} or {@link Converter#toJsonString} is first called.
     */
    private static final class Mapper {

        private static final ObjectMapper MAPPER = createMapper();
        private static final ObjectReader READER = MAPPER.readerFor(RawGeoModel.class);
        private static final ObjectWriter WRITER = MAPPER.writerFor(RawGeoModel.class);

        private Mapper() {}
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        // YSMU: allow // and /* */ comments in model JSON. Some YSM models exported
        // from third-party tools embed comments (e.g. inside ysm_extra_info), which
        // otherwise fail both server-side cache building (YsmFormat.getBytes) and
        // client-side first-load parsing.
        mapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // Model packs are third-party data. Bedrock exporters add, rename and drop geometry fields freely
        // (tool version stamps, custom markers, ...), and these POJOs model only the subset the engine reads,
        // so an unmodelled field must be ignored instead of aborting the whole document.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleModule module = new SimpleModule();
        module.addDeserializer(OffsetDateTime.class, new JsonDeserializer<OffsetDateTime>() {

            @Override
            public OffsetDateTime deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                throws IOException, JsonProcessingException {
                String value = jsonParser.getText();
                return Converter.parseDateTimeString(value);
            }
        });
        mapper.registerModule(module);
        return mapper;
    }
}
