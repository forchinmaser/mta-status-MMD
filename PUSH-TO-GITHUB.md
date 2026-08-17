# Pushing this project to forchinmaser/mta-status-MMD

I can read that repo but I cannot push to it — my GitHub access is read-only, so
the commit has to come from your machine. It currently holds `LICENSE` and a
16-byte `README.md` on `main`.

Download the project zip from the chat, unzip it, then:

    cd ~/Downloads/mudita-kompakt-mobile-app     # wherever you unzipped

    git init
    git branch -M main
    git remote add origin git@github.com:forchinmaser/mta-status-MMD.git

    # keep the LICENSE and README already on main
    git fetch origin main
    git reset --soft origin/main

    git add .
    git commit -m "Transit for Kompakt: MMD Compose app + live MTA prototype"
    git push -u origin main

If you use HTTPS instead of SSH, swap the remote for
`https://github.com/forchinmaser/mta-status-MMD.git`.

## What lands

    android/                       Gradle project (:app + :mmd-core)
    android/README.md              architecture, ghosting rules, data sources
    android/BUILD-ON-MAC.md        running prodDebug on the Kompakt
    transit-kompakt-prototype.html live browser prototype / layout spec
    github.md                      provenance for the MMD import

## .gitignore

A `.gitignore` is included covering Gradle and Android Studio output
(`build/`, `.gradle/`, `local.properties`, `*.iml`, `.idea/`). The Gradle
wrapper jar is not in the project — Studio generates it on first open, and
`gradle/wrapper/gradle-wrapper.properties` is pinned to Gradle 8.7.

## Note on MMD's license

`android/mmd-core` is MMD's source, Apache-2.0, with its `LICENSE` retained in
the module folder. Keep that file in place, and keep the attribution note at the
top of `mmd-core/build.gradle.kts`, when the code is redistributed.
