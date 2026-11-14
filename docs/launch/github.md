# GitHub

The code is committed locally (branch `main`). Once you are signed in to GitHub in the Claude browser
(or give me a GitHub token), I can create the repository and push. By hand:

1. Create a new public repository named `foremon` at https://github.com/new. No README, no licence (both
   are already in the project).
2. Description: `Put your Cobblemon to work. Fabric 1.21.1 addon. Developed with AI assistance.`
3. Topics: `minecraft`, `minecraft-mod`, `fabric`, `cobblemon`, `pokemon`, `kotlin`.
4. In the project folder:

```bash
git remote add origin https://github.com/arusadusdg/foremon.git
```

```bash
git push -u origin main
```

5. Settings, General: keep Issues on. Add two issue labels: `bug` and `idea`.
6. Releases: create release `v1.0.0`, attach `build/libs/foremon-1.0.0.jar`, paste the 1.0.0 changelog.

## Before the code is public

`gradle.properties` has two lines that only fit this PC: `org.gradle.java.home=...` and the
`-Djdk.net.unixdomain.tmpdir=C:/gtmp` part of `org.gradle.jvmargs`. They do no harm on other PCs except
the Java path, which will not exist there. Move both into `C:/Users/Administrator/.gradle/gradle.properties`
before pushing, or ask me to.

The commits use the name "Foremon" and a placeholder email, not your personal details.
