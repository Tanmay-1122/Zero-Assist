// GENERATED stub — empty descriptions until the OpenAPI spec is available.
export type FieldDescriptions = Record<string, Record<string, string>>;
export const fieldDescriptions: FieldDescriptions = {};
export function fieldHelp(schema: string, field: string): string | undefined {
  return fieldDescriptions[schema]?.[field];
}
