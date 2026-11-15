import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const modelPath = path.join(
  root,
  "core/model/src/main/java/com/weich/daptune/core/model/EqCurve.kt",
);
const presetsPath = path.join(
  root,
  "core/eq/src/main/java/com/weich/daptune/core/eq/BuiltInPresets.kt",
);
const schemaPath = path.join(root, "docs/schema/daptune-profile-v1.schema.json");
const examplesDirectory = path.join(root, "examples/profiles");

const model = await readFile(modelPath, "utf8");
const presets = await readFile(presetsPath, "utf8");
const schema = JSON.parse(await readFile(schemaPath, "utf8"));
const schemaV2 = JSON.parse(await readFile(path.join(root, "docs/schema/daptune-profile-v2.schema.json"), "utf8"));
const codec = await readFile(path.join(root, "core/eq/src/main/java/com/weich/daptune/core/eq/CurveFileCodec.kt"), "utf8");
const frequencyBlock = model.match(/frequenciesHz:\s*IntArray\s*=\s*intArrayOf\(([\s\S]*?)\)/);
assert(frequencyBlock, "Cannot locate DapBandPlan.frequenciesHz in EqCurve.kt");
const frequencies = [...frequencyBlock[1].matchAll(/\d+/g)].map((match) => Number(match[0]));
const bandPlan = model.match(/const val id: String = "([^"]+)"/)?.[1];
const bandCount = Number(model.match(/const val bandCount: Int = (\d+)/)?.[1]);
const q4PerDb = Number(model.match(/const val Q4_PER_DB = (\d+)/)?.[1]);
const legacyMaximumQ4 = Number(codec.match(/const val LegacyMaximumGainQ4 = (\d+)/)?.[1]);

assert.equal(bandPlan, "dolby-dap-20-v1");
assert.equal(bandCount, frequencies.length);
assert.equal(q4PerDb, 16);
assert.equal(legacyMaximumQ4, 160);
assert.equal(Number(codec.match(/const val LegacyFileVersion = (\d+)/)?.[1]), 1);
assert.equal(Number(codec.match(/const val ExtendedFileVersion = (\d+)/)?.[1]), 2);
assert.deepEqual(schema.properties.frequencies_hz.const, frequencies);
assert.equal(schema.properties.band_plan.const, bandPlan);
assert.equal(schema.properties.gains_q4.minItems, bandCount);
assert.equal(schema.properties.gains_q4.maxItems, bandCount);
assert.equal(schema.properties.gains_q4.items.maximum, legacyMaximumQ4);
assert.equal(schemaV2.properties.gains_q4.items.maximum, 2147483647);
assert.equal(schemaV2.properties.gains_q4.items.minimum, -2147483648);
assert.equal(schemaV2.properties.version.const, 2);
assert.equal(schemaV2.properties.gains_q4.minItems, bandCount);
assert.equal(schemaV2.properties.gains_q4.maxItems, bandCount);
assert.deepEqual(schemaV2.properties.frequencies_hz.const, frequencies);
assert.deepEqual(schemaV2.required, schema.required);
assert.deepEqual(Object.keys(schemaV2.properties), Object.keys(schema.properties));
assert.equal(schemaV2.additionalProperties, false);
assert.equal(schemaV2.properties.format.const, schema.properties.format.const);
assert.equal(schemaV2.properties.band_plan.const, bandPlan);

const required = new Set(schema.required);
const allowed = new Set(Object.keys(schema.properties));
const exampleFiles = (await readdir(examplesDirectory))
  .filter((name) => name.endsWith(".daptune.json"))
  .sort();
assert(exampleFiles.length >= 3, "At least three native profile examples are required");

for (const fileName of exampleFiles) {
  const document = JSON.parse(await readFile(path.join(examplesDirectory, fileName), "utf8"));
  assert.deepEqual(new Set(Object.keys(document)), required, `${fileName}: fields must exactly match v1`);
  assert(Object.keys(document).every((key) => allowed.has(key)), `${fileName}: unknown field`);
  assert.equal(document.format, schema.properties.format.const, `${fileName}: format`);
  const documentSchema = new Map([[1, schema], [2, schemaV2]]).get(document.version);
  assert(documentSchema, `${fileName}: unsupported version`);
  assert.equal(document.band_plan, bandPlan, `${fileName}: band_plan`);
  assert.deepEqual(document.frequencies_hz, frequencies, `${fileName}: frequencies_hz`);
  assert.equal(document.name, document.name.trim(), `${fileName}: name must be trimmed`);
  assert(document.name.length > 0 && document.name.length <= 40, `${fileName}: name length`);
  assert.equal(document.gains_q4.length, bandCount, `${fileName}: gains_q4 length`);
  assert(
    document.gains_q4.every(
      (gain) => Number.isInteger(gain) && gain >= -2147483648 && gain <= documentSchema.properties.gains_q4.items.maximum,
    ),
    `${fileName}: gains_q4 value`,
  );
}

const warmBlock = presets.match(
  /preset\("builtin\.warm",\s*"[^"]+",\s*\d+,\s*doubleArrayOf\(([\s\S]*?)\)\)/,
);
assert(warmBlock, "Cannot locate builtin.warm in BuiltInPresets.kt");
const warmQ4 = [...warmBlock[1].matchAll(/-?\d+(?:\.\d+)?/g)]
  .map((match) => Math.round(Number(match[0]) * q4PerDb));
const warmExample = JSON.parse(
  await readFile(path.join(examplesDirectory, "warm.daptune.json"), "utf8"),
);
assert.deepEqual(warmExample.gains_q4, warmQ4, "warm example must match builtin.warm exactly");

console.log(`Validated DapTune profile v1/v2 contracts and ${exampleFiles.length} examples.`);
