import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import jsdoc from "eslint-plugin-jsdoc";

/**
 * Lailo documentation rule (mirrors the backend's Checkstyle rule):
 * every named function, component, hook, method, class, type alias and interface - exported or not, app or test
 * code - has a JSDoc comment. Every `test(...)` / `it(...)` states its condition as Given / When / Then.
 * Inline callbacks (`onClick={() => ...}`, `.map(x => ...)`) and the bodies passed to `test()` are exempt.
 */
const jsdocRules = {
  files: ["**/*.{ts,tsx,js,mjs}"],
  plugins: { jsdoc },
  settings: { jsdoc: { mode: "typescript" } },
  rules: {
    "jsdoc/require-jsdoc": [
      "error",
      {
        publicOnly: false,
        checkConstructors: true,
        checkGetters: true,
        checkSetters: true,
        require: {
          FunctionDeclaration: true,
          FunctionExpression: true,
          ArrowFunctionExpression: true,
          MethodDefinition: true,
          ClassDeclaration: true,
          ClassExpression: true,
        },
        contexts: [
          "TSTypeAliasDeclaration",
          "TSInterfaceDeclaration",
          "TSEnumDeclaration",
          // test cases: Playwright test(...), Vitest it(...) / test(...), and it.each(...)(...)
          "ExpressionStatement > CallExpression[callee.name=/^(test|it)$/]",
          "ExpressionStatement > CallExpression[callee.callee.object.name=/^(test|it)$/][callee.callee.property.name='each']",
        ],
      },
    ],
    // keep the comments well-formed; types live in TypeScript, not in JSDoc
    "jsdoc/require-description": "error",
    "jsdoc/no-blank-block-descriptions": "error",
    "jsdoc/check-alignment": "error",
    "jsdoc/check-param-names": "error",
    "jsdoc/check-tag-names": "error",
    "jsdoc/empty-tags": "error",
    "jsdoc/no-multi-asterisks": "error",
    "jsdoc/no-types": "error",
  },
};

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  jsdocRules,
  // Playwright fixtures call `use()`, which is not React's hook.
  { files: ["e2e/**"], rules: { "react-hooks/rules-of-hooks": "off" } },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
