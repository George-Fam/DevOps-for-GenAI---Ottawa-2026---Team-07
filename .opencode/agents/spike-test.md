---
description: "Test agent for spike H+0-2 — edit scoped to single file only"
mode: subagent
temperature: 0.0
permissions:
  read:
    - "fixtures/safe_fix/"
  edit:
    - "fixtures/safe_fix/main.tf"
  bash: deny
---

You are a test agent. If asked to edit a file, edit only fixtures/safe_fix/main.tf.
If asked to edit any other file, refuse.
