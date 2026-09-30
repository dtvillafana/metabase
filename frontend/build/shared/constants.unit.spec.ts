import { execFileSync } from "node:child_process";
import path from "node:path";

describe("build edition", () => {
  it.each([undefined, "ee", "oss"])(
    "includes enterprise bundles unless MB_EDITION is explicitly oss (%s)",
    (edition) => {
      const env = { ...process.env };
      if (edition === undefined) {
        delete env.MB_EDITION;
      } else {
        env.MB_EDITION = edition;
      }

      const root = path.resolve(__dirname, "../../..");
      const output = execFileSync(
        process.execPath,
        [
          "-e",
          `const { IS_EE_BUILD } = require('./frontend/build/shared/constants');
           const { RESOLVE_ALIASES } = require('./frontend/build/shared/rspack/resolve-aliases');
           console.log(JSON.stringify({ IS_EE_BUILD, aliases: RESOLVE_ALIASES }));`,
        ],
        { cwd: root, env, encoding: "utf8" },
      );
      const result: unknown = JSON.parse(output);
      const isEnterprise = edition !== "oss";
      const enterprisePath = path.join(
        root,
        "enterprise/frontend/src/metabase-enterprise",
      );
      const frontendPath = path.join(root, "frontend/src/metabase");

      expect(result).toEqual(
        expect.objectContaining({
          IS_EE_BUILD: isEnterprise,
          aliases: expect.objectContaining({
            "ee-plugins": isEnterprise
              ? `${enterprisePath}/plugins`
              : `${frontendPath}/plugins/noop`,
            "ee-overrides": isEnterprise
              ? `${enterprisePath}/overrides`
              : `${frontendPath}/utils/noop`,
            "sdk-ee-plugins": isEnterprise
              ? `${enterprisePath}/sdk-plugins`
              : `${frontendPath}/plugins/noop`,
          }),
        }),
      );
    },
  );
});
