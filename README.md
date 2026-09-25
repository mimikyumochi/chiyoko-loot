# Chiyoko

Chiyoko predicts the outcome of certain loot tables before they happen by simulating the RNG exactly as minecraft does.

- supports minecraft **26.1**, **26.2** and **26.3** (fabric)
- 1.21.11 support is currently being worked on, this might take a while.
- enchantment prediction has moved to [Chiyoko Player](https://github.com/mimikyumochi/chiyoko-player)

---

## Features

### Loot Prediction
supports prediction for:
- wither skeleton drops
- fishing
- piglin bartering
- gravel breaking
- trial vaults (normal and ominous)
- shulker drops

### Customisation
- draggable overlay
- configurable visibility and tracking
- rotation and reversal options
- optional split vault overlay for less confusion

### Multiplayer Support
- attempts to automatically recover from RNG desyncs by advancing until the predicted result matches the actual outcome

---

## Supported Loot Tables

| loot source | prediction |
|-------------|------------|
| wither skeleton | next drops, kills until skull |
| fishing | next item |
| piglin bartering | next item |
| gravel breaking | next item |
| trial vault | next drops |
| ominous vault | next drops |
| shulker | next drops, kills until shell |

---

## Commands

### `/validateseed`
verifies that the world seed configured in chiyoko matches the server's seed hash

---

## Limitations

- cannot predict **unstable RNG**, such as the number of bones required to tame a wolf
- cannot automatically obtain the world seed in multiplayer
- uses the **1.20+** loot table system and therefore cannot support versions before **1.20**

---

## Support

if you have any issues please message me on discord (`mimikyumochi`) or send me a dm on twitter ([@mimikyumochii](https://twitter.com/mimikyumochii))
