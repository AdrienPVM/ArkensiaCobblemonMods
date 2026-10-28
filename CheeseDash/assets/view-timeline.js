// ── Timeline Editor ──────────────────────────────────────────────────────────
const MC_COLORS = ['black','dark_blue','dark_green','dark_aqua','dark_red','dark_purple','gold','gray','dark_gray','blue','green','aqua','red','light_purple','yellow','white'];
const MC_POTIONS = ['speed','slowness','haste','mining_fatigue','strength','instant_health','instant_damage','jump_boost','nausea','regeneration','resistance','fire_resistance','water_breathing','invisibility','blindness','night_vision','hunger','weakness','poison','wither','health_boost','absorption','saturation','glowing','levitation','luck','unluck','slow_falling','conduit_power','dolphins_grace','bad_omen','hero_of_the_village','darkness','trial_omen','raid_omen','wind_charged','weaving','oozing','infested'];
const MC_PARTICLES = ['ambient_entity_effect','angry_villager','block','bubble','cloud','crit','damage_indicator','dragon_breath','dripping_lava','dripping_water','dust','effect','elder_guardian','enchant','enchanted_hit','end_rod','entity_effect','explosion','explosion_emitter','falling_dust','firework','fishing','flame','flash','happy_villager','heart','instant_effect','item','item_slime','item_snowball','large_smoke','lava','mycelium','note','poof','portal','rain','smoke','sneeze','spit','splash','squid_ink','sweep_attack','totem_of_undying','underwater','witch','bubble_pop','current_down','bubble_column_up','nautilus','dolphin','campfire_cosy_smoke','campfire_signal_smoke','dripping_honey','falling_honey','landing_honey','falling_nectar','soul_fire_flame','ash','crimson_spore','warped_spore','soul','dripping_obsidian_tear','reverse_portal','white_ash','dust_color_transition','vibration','small_flame','snowflake','glow','glow_squid_ink','electric_spark','scrape','shriek','cherry_leaves','egg_crack','dust_plume','trial_spawner_detection'];
const MC_KNOCKBACK_DIRS = ['up','north','south','east','west','center','away','random'];
const MC_DEATH_BEHAVIORS = ['default','spectator','respawn_at_spawn','respawn_at_position','eliminate'];

// ── Timeline tracks (matches TimelineBlock.java TRACK_* constants) ───────────
const TL_TRACKS = [
  {id:'messages',    icon:'💬', label:'tl.cat.messages',    color:'#3b82f6'},
  {id:'potions',     icon:'🧪', label:'tl.cat.potions',     color:'#a855f7'},
  {id:'zones',       icon:'🗺', label:'tl.cat.zones',       color:'#f59e0b'},
  {id:'spawns',      icon:'👾', label:'tl.cat.spawns',      color:'#ef4444'},
  {id:'commands',    icon:'⚡', label:'tl.cat.commands',    color:'#eab308'},
  {id:'weather',     icon:'🌦', label:'tl.cat.weather',     color:'#06b6d4'},
  {id:'sounds',      icon:'🎵', label:'tl.cat.sounds',      color:'#ec4899'},
  {id:'score',       icon:'🏆', label:'tl.cat.score',       color:'#f5a623'},
  {id:'flags',       icon:'🚩', label:'tl.cat.flags',       color:'#64748b'},
  {id:'roles',       icon:'🎭', label:'tl.cat.roles',       color:'#8b5cf6'},
  {id:'votes',       icon:'🗳', label:'tl.cat.votes',       color:'#14b8a6'},
  {id:'players',     icon:'❄',  label:'tl.cat.players',     color:'#22d3ee'},
  {id:'state_flags', icon:'🔑', label:'tl.cat.state_flags', color:'#78716c'},
  {id:'random',      icon:'🎰', label:'tl.cat.random',      color:'#d946ef'},
  {id:'objectives',  icon:'🎯', label:'tl.cat.objectives',  color:'#f97316'},
  {id:'death',       icon:'💀', label:'tl.cat.death',       color:'#dc2626'},
  {id:'dimension',   icon:'🌐', label:'tl.cat.dimension',   color:'#2dd4bf'},
  {id:'inventory',   icon:'🎒', label:'tl.cat.inventory',   color:'#84cc16'},
  {id:'visibility',  icon:'👁', label:'tl.cat.visibility',  color:'#6366f1'},
  {id:'redstone',    icon:'🔴', label:'tl.cat.redstone',    color:'#b91c1c'},
  {id:'stats',       icon:'📊', label:'tl.cat.stats',       color:'#0ea5e9'},
  {id:'logic',       icon:'🔗', label:'tl.cat.logic',       color:'#94a3b8'},
];

// ── Mini-game config schema (per mini-game type) ─────────────────────────────
const MINIGAME_CONFIG_SCHEMA = {
  THIMBLE: {
    label: 'mg.thimble',
    fields: [
      {k:'borderBlock',l:'mg.field.borderBlock',            t:'text',   def:'minecraft:stone',   tip:'mg.tip.borderBlock'},
      {k:'platformZoneId',l:'mg.field.platformZoneId',      t:'text',   def:'', dl:'global-zones-dl', tip:'mg.tip.platformZoneId'},
      {k:'suddenDeathPlatformZoneId',l:'mg.field.suddenDeathPlatformZoneId', t:'text', def:'', dl:'global-zones-dl', tip:'mg.tip.suddenDeathPlatformZoneId'},
      {k:'startingLives',l:'mg.field.startingLives',        t:'number', def:1,    min:1,  max:5,   tip:'mg.tip.startingLives'},
      {k:'maxLives',l:'mg.field.maxLives',               t:'number', def:3,    min:1,  max:10,  tip:'mg.tip.maxLives'},
      {k:'turnTimeoutSeconds',l:'mg.field.turnTimeoutSeconds',           t:'number', def:30,   min:10, max:120, tip:'mg.tip.turnTimeoutSeconds'},
      {k:'pointsPerJump',l:'mg.field.pointsPerJump',             t:'number', def:1,    min:0,  max:10,  tip:'mg.tip.pointsPerJump'},
      {k:'thimbleBonusPoints',l:'mg.field.thimbleBonusPoints',  t:'number', def:4,    min:0,  max:20,  tip:'mg.tip.thimbleBonusPoints'},
      {k:'suddenDeathEnabled',l:'mg.field.suddenDeathEnabled',              t:'bool',   def:true,                    tip:'mg.tip.suddenDeathEnabled'},
      {k:'suddenDeathPoints',l:'mg.field.suddenDeathPoints',       t:'number', def:2,    min:0,  max:10,  tip:'mg.tip.suddenDeathPoints'},
      {k:'suddenDeathTp',l:'mg.field.suddenDeathTp', t:'tpSection', tip:'mg.tip.suddenDeathTp'},
    ]
  },
  SPLEEF: {
    label: 'mg.spleef',
    fields: [
      {k:'countdownSeconds',l:'mg.field.countdownSeconds',       t:'number', def:5,   min:0,  max:30,  tip:'mg.tip.countdownSeconds'},
      {k:'survivalPointsPerSec',l:'mg.field.survivalPointsPerSec',       t:'number', def:1,   min:0,  max:10,  tip:'mg.tip.survivalPointsPerSec'},
      {k:'victoryPoints',l:'mg.field.victoryPoints',           t:'number', def:100, min:0,  max:500, tip:'mg.tip.victoryPoints'},
      {k:'layerTransitionMessage',l:'mg.field.layerTransitionMessage', t:'bool',   def:true,                  tip:'mg.tip.layerTransitionMessage'},
      {k:'degradeEnabled',l:'mg.field.degradeEnabled',   t:'bool',   def:false,                 tip:'mg.tip.degradeEnabled'},
      {k:'degradeDelaySeconds',l:'mg.field.degradeDelaySeconds',    t:'number', def:30,  min:0,  max:600, tip:'mg.tip.degradeDelaySeconds'},
      {k:'degradeBlocksPerTick',l:'mg.field.degradeBlocksPerTick',           t:'number', def:5,   min:1,  max:20,  tip:'mg.tip.degradeBlocksPerTick'},
      {k:'degradeThresholdPercent',l:'mg.field.degradeThresholdPercent', t:'number', def:80,  min:10, max:100, tip:'mg.tip.degradeThresholdPercent'},
    ]
  },
  FISHING_CONTEST: { label: 'mg.fishing_contest', fields: [] },
  DEATHMATCH:      { label: 'mg.deathmatch',       fields: [] },
  PARKOUR:         { label: 'mg.parkour',          fields: [] },
  RACE: {
    label: 'mg.race',
    fields: [
      {k:'checkpointZoneIds',l:'mg.field.checkpointZoneIds', t:'zonelist', def:[],
       tip:'mg.tip.checkpointZoneIds'},
      {k:'transformCommands',l:'mg.field.transformCommands', t:'cmdlist', def:[],
       tip:'mg.tip.transformCommands'},
      {k:'transformMode',l:'mg.field.transformMode', t:'select',
       opts:[{v:'command',l:'mg.opt.command'},{v:'species',l:'mg.opt.species'}], def:'command',
       tip:'mg.tip.transformMode'},
      {k:'powerUpZones',l:'mg.field.powerUpZones', t:'poweruplist', def:[],
       tip:'mg.tip.powerUpZones'},
      {k:'countdownSeconds',l:'mg.field.countdownSeconds',      t:'number', def:5,   min:0,  max:30},
      {k:'endTimerSeconds',l:'mg.field.endTimerSeconds',   t:'number', def:30,  min:5,  max:300},
      {k:'pointsPerCheckpoint',l:'mg.field.pointsPerCheckpoint',       t:'number', def:10,  min:0,  max:100},
      {k:'finishBonusPoints',l:'mg.field.finishBonusPoints',             t:'number', def:100, min:0,  max:500},
      {k:'positionBonusPoints',l:'mg.field.positionBonusPoints',      t:'number', def:50,  min:0,  max:500},
      {k:'checkpointSound',l:'mg.field.checkpointSound',            t:'text',   def:'block.note_block.bell'},
      {k:'finishSound',l:'mg.field.finishSound',               t:'text',   def:'ui.toast.challenge_complete'},
      {k:'freezeDuringCountdown',l:'mg.field.freezeDuringCountdown',  t:'bool',   def:true},
      {k:'revertTransformsOnEnd',l:'mg.field.revertTransformsOnEnd', t:'bool',   def:true},
      {k:'showActionbar',l:'mg.field.showActionbar',     t:'bool',   def:true},
    ]
  },
  KING_OF_HILL:    { label: 'mg.king_of_hill', fields: [] },
  PROP_HUNT: {
    label: 'mg.prophunt',
    fields: [
      {k:'hunterCount',l:'mg.field.ph.hunterCount',t:'text',def:'2',tip:'mg.tip.ph.hunterCount',ph:'2 or 1/4'},
      {k:'hunterStartHearts',l:'mg.field.ph.hunterHearts',t:'number',def:10,min:1,max:20,tip:'mg.tip.ph.hunterHearts'},
      {k:'hiderHearts',l:'mg.field.ph.hiderHearts',t:'number',def:3,min:1,max:20,tip:'mg.tip.ph.hiderHearts'},
      {k:'penaltyHearts',l:'mg.field.ph.penaltyHearts',t:'number',def:1,min:1,max:5,tip:'mg.tip.ph.penaltyHearts'},
      {k:'hideGraceSeconds',l:'mg.field.ph.graceTime',t:'number',def:30,min:5,max:120,tip:'mg.tip.ph.graceTime'},
      {k:'gameDurationSeconds',l:'mg.field.ph.gameDuration',t:'number',def:300,min:60,max:900,tip:'mg.tip.ph.gameDuration'},
      {k:'speciesList',l:'mg.field.ph.speciesList',t:'specieslist',def:[],tip:'mg.tip.ph.speciesList'},
      {k:'decoyCountPerHider',l:'mg.field.ph.decoyPerHider',t:'number',def:5,min:1,max:30,tip:'mg.tip.ph.decoyPerHider'},
      {k:'attacksEnabled',l:'mg.field.ph.attacksEnabled',t:'bool',def:false,tip:'mg.tip.ph.attacksEnabled'},
      {k:'transformCooldownTicks',l:'mg.field.ph.transformCooldown',t:'number',def:200,min:0,max:1200,tip:'mg.tip.ph.transformCooldown'},
      {k:'hunterWeapon',l:'mg.field.ph.hunterWeapon',t:'select',def:'bow',tip:'mg.tip.ph.hunterWeapon',opts:[
        {v:'bow',l:'mg.opt.ph.bow'},{v:'crossbow',l:'mg.opt.ph.crossbow'},
        {v:'sword',l:'mg.opt.ph.sword'},{v:'bow_sword',l:'mg.opt.ph.bowSword'}
      ]},
      {k:'hunterArrowCount',l:'mg.field.ph.arrowCount',t:'number',def:64,min:1,max:256,tip:'mg.tip.ph.arrowCount'},
      {k:'spawnZoneIds',l:'mg.field.ph.spawnZones',t:'zonelist',def:[],tip:'mg.tip.ph.spawnZones'},
      {k:'hunterTpZoneId',l:'mg.field.ph.hunterZone',t:'text',def:'',tip:'mg.tip.ph.hunterZone',dl:'global-zones-dl'},
      {k:'hunterPowerCount',l:'mg.field.ph.hunterPowerCount',t:'number',def:3,min:0,max:10,tip:'mg.tip.ph.hunterPowerCount'},
      {k:'hunterPowerConfig',l:'mg.field.ph.hunterPowerConfig',t:'powerconfig',role:'hunter',
       powers:[
         {id:'SONAR',icon:'\uD83D\uDCE1',def:{enabled:true,cooldown:30}},
         {id:'SCRAMBLE',icon:'\uD83D\uDD00',def:{enabled:true,cooldown:45}},
         {id:'REVEAL',icon:'\uD83D\uDC41',def:{enabled:true,cooldown:20}},
         {id:'FREEZE',icon:'\u2744',def:{enabled:true,cooldown:30}},
         {id:'COMPASS',icon:'\uD83E\uDDED',def:{enabled:true,cooldown:30}},
         {id:'TRAP',icon:'\uD83D\uDD78',def:{enabled:true,cooldown:30}},
         {id:'ECHOLOCATION',icon:'\uD83E\uDD87',def:{enabled:true,cooldown:25}},
         {id:'FLARE',icon:'\uD83D\uDD25',def:{enabled:true,cooldown:15}},
         {id:'SNIFF',icon:'\uD83D\uDC43',def:{enabled:true,cooldown:20}},
         {id:'HUNTER_MARK',icon:'\uD83C\uDFAF',def:{enabled:true,cooldown:40}}
       ]},
      {k:'hiderPowerCount',l:'mg.field.ph.hiderPowerCount',t:'number',def:2,min:0,max:10,tip:'mg.tip.ph.hiderPowerCount'},
      {k:'hiderPowerConfig',l:'mg.field.ph.hiderPowerConfig',t:'powerconfig',role:'hider',
       powers:[
         {id:'DECOY',icon:'\uD83C\uDFAD',def:{enabled:true,cooldown:20}},
         {id:'INVISIBILITY',icon:'\uD83D\uDC7B',def:{enabled:true,cooldown:30}},
         {id:'SMOKESCREEN',icon:'\uD83D\uDCA8',def:{enabled:true,cooldown:25}},
         {id:'MIMIC',icon:'\uD83D\uDDFF',def:{enabled:true,cooldown:35}},
         {id:'TAUNT',icon:'\uD83D\uDCE2',def:{enabled:true,cooldown:20}},
         {id:'SHIELD',icon:'\uD83D\uDEE1',def:{enabled:true,cooldown:30}},
         {id:'HASTE',icon:'\uD83D\uDCA8',def:{enabled:true,cooldown:15}},
         {id:'DISTRACT',icon:'\uD83C\uDF86',def:{enabled:true,cooldown:20}}
       ]},
    ]
  },
};
const MC_SOUND_HINTS = ['entity.player.levelup','entity.experience_orb.pickup','entity.ender_dragon.growl','entity.wither.spawn','block.note_block.pling','block.note_block.bass','block.note_block.chime','block.beacon.activate','ui.button.click','ui.toast.challenge_complete','ambient.cave','ambient.underwater.loop','music.game','music.nether.basalt_deltas','entity.firework_rocket.launch','entity.lightning_bolt.thunder','entity.villager.celebrate','entity.wolf.howl','block.anvil.land','block.bell.use','entity.zombie.ambient','entity.creeper.primed','entity.blaze.shoot','entity.phantom.ambient','entity.ghast.scream'];

const TL_CONDITIONS = [
  {t:'NONE',l:'tl.cond.NONE',tip:'tl.cond.NONE.tip'},
  {t:'PLAYER_COUNT_BELOW',l:'tl.cond.PLAYER_COUNT_BELOW',tip:'tl.cond.PLAYER_COUNT_BELOW.tip'},
  {t:'PLAYER_COUNT_ABOVE',l:'tl.cond.PLAYER_COUNT_ABOVE',tip:'tl.cond.PLAYER_COUNT_ABOVE.tip'},
  {t:'SCORE_ABOVE',l:'tl.cond.SCORE_ABOVE',tip:'tl.cond.SCORE_ABOVE.tip'},
  {t:'SCORE_BELOW',l:'tl.cond.SCORE_BELOW',tip:'tl.cond.SCORE_BELOW.tip'},
  {t:'TEAM_SCORE_ABOVE',l:'tl.cond.TEAM_SCORE_ABOVE',tip:'tl.cond.TEAM_SCORE_ABOVE.tip'},
  {t:'TIME_OF_DAY_DAY',l:'tl.cond.TIME_OF_DAY_DAY',tip:'tl.cond.TIME_OF_DAY_DAY.tip'},
  {t:'TIME_OF_DAY_NIGHT',l:'tl.cond.TIME_OF_DAY_NIGHT',tip:'tl.cond.TIME_OF_DAY_NIGHT.tip'},
  {t:'PLAYER_FLAG_TRUE',l:'tl.cond.PLAYER_FLAG_TRUE',tip:'tl.cond.PLAYER_FLAG_TRUE.tip'},
  {t:'PLAYER_FLAG_FALSE',l:'tl.cond.PLAYER_FLAG_FALSE',tip:'tl.cond.PLAYER_FLAG_FALSE.tip'},
  {t:'RANDOM_CHANCE',l:'tl.cond.RANDOM_CHANCE',tip:'tl.cond.RANDOM_CHANCE.tip'},
];

// Effect property definitions — desc: effect description (FR), props: property array, tip: property tooltip
// New prop types: t:'sound' (text+datalist+play), t:'list' (multi-row add/remove)
const TL_EFFECT_PROPS = {
  BROADCAST:{desc:"tl.desc.BROADCAST",props:[
    {k:'message',l:'tl.prop.message',t:'text',tip:'tl.tip.message'},
    {k:'messageColor',l:'tl.prop.messageColor',t:'select',opts:MC_COLORS,tip:'tl.tip.messageColor'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num',tip:'tl.tip.intervalSeconds'},
    {k:'chancePercent',l:'tl.prop.chancePercent',t:'num',min:0,max:100,tip:'tl.tip.chancePercent'}]},
  ACTIONBAR_PERSISTENT:{desc:"tl.desc.ACTIONBAR_PERSISTENT",props:[
    {k:'message',l:'tl.prop.message',t:'text',tip:'tl.tip.message'},
    {k:'color',l:'tl.prop.color',t:'select',opts:MC_COLORS,tip:'tl.tip.color'},
    {k:'interval',l:'tl.prop.interval',t:'num',tip:'tl.tip.interval'}]},
  TITLE:{desc:"tl.desc.TITLE",props:[
    {k:'titleText',l:'tl.prop.titleText',t:'text',tip:'tl.tip.titleText'},
    {k:'subtitleText',l:'tl.prop.subtitleText',t:'text',tip:'tl.tip.subtitleText'},
    {k:'titleColor',l:'tl.prop.titleColor',t:'select',opts:MC_COLORS},
    {k:'fadeIn',l:'tl.prop.fadeIn',t:'num',tip:'tl.tip.fadeIn'},
    {k:'stay',l:'tl.prop.stay',t:'num',tip:'tl.tip.stay'},
    {k:'fadeOut',l:'tl.prop.fadeOut',t:'num',tip:'tl.tip.fadeOut'},
    {k:'interval',l:'tl.prop.interval',t:'num',tip:'tl.tip.interval'}]},
  POTION:{desc:"tl.desc.POTION",props:[
    {k:'potionId',l:'tl.prop.potionId',t:'select',opts:MC_POTIONS,tip:'tl.tip.potionId'},
    {k:'amplifier',l:'tl.prop.amplifier',t:'num',min:0,max:9,tip:'tl.tip.amplifier'},
    {k:'potionDurationTicks',l:'tl.prop.potionDurationTicks',t:'num',tip:'tl.tip.potionDurationTicks'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num',tip:'tl.tip.intervalSeconds'}]},
  COMMAND:{desc:"tl.desc.COMMAND",props:[
    {k:'command',l:'tl.prop.command',t:'text',tip:'tl.tip.command'},
    {k:'radiusBlocks',l:'tl.prop.radiusBlocks',t:'num',tip:'tl.tip.radiusBlocks'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num',tip:'tl.tip.intervalSeconds'},
    {k:'chancePercent',l:'tl.prop.chancePercent',t:'num',min:0,max:100,tip:'tl.tip.chancePercent'}]},
  COMMAND_PLAYER:{desc:"tl.desc.COMMAND_PLAYER",props:[
    {k:'command',l:'tl.prop.command',t:'text',tip:'tl.tip.command'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num',tip:'tl.tip.intervalSeconds'}]},
  WEATHER:{desc:"tl.desc.WEATHER",props:[
    {k:'weatherType',l:'tl.prop.weatherType',t:'select',opts:['clear','rain','thunder'],tip:'tl.tip.weatherType'}]},
  TIME:{desc:"tl.desc.TIME",props:[
    {k:'timeValue',l:'tl.prop.timeValue',t:'num',min:0,max:23999,tip:'tl.tip.timeValue'},
    {k:'freezeTime',l:'tl.prop.freezeTime',t:'bool',tip:'tl.tip.freezeTime'}]},
  SOUND:{desc:"tl.desc.SOUND",props:[
    {k:'soundId',l:'tl.prop.soundId',t:'sound',tip:'tl.tip.soundId'},
    {k:'soundVolume',l:'tl.prop.soundVolume',t:'num',min:0,max:1,step:0.1,tip:'tl.tip.soundVolume'},
    {k:'soundPitch',l:'tl.prop.soundPitch',t:'num',min:0.5,max:2,step:0.1,tip:'tl.tip.soundPitch'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num',tip:'tl.tip.intervalSeconds'}]},
  PARTICLE:{desc:"tl.desc.PARTICLE",props:[
    {k:'particleId',l:'tl.prop.particleId',t:'select',opts:MC_PARTICLES,tip:'tl.tip.particleId'},
    {k:'particleCount',l:'tl.prop.particleCount',t:'num',tip:'tl.tip.particleCount'},
    {k:'particleSpread',l:'tl.prop.particleSpread',t:'num',tip:'tl.tip.particleSpread'},
    {k:'interval',l:'tl.prop.interval',t:'num',tip:'tl.tip.interval'}]},
  ZONE_SHRINK:{desc:"tl.desc.ZONE_SHRINK",props:[
    {k:'zoneCenterX',l:'tl.prop.zoneCenterX',t:'num',pos:true},{k:'zoneCenterY',l:'tl.prop.zoneCenterY',t:'num',pos:true},{k:'zoneCenterZ',l:'tl.prop.zoneCenterZ',t:'num',pos:true},
    {k:'radiusInitial',l:'tl.prop.radiusInitial',t:'num',tip:'tl.tip.radiusInitial'},
    {k:'radiusFinal',l:'tl.prop.radiusFinal',t:'num',tip:'tl.tip.radiusFinal'},
    {k:'zoneDamage',l:'tl.prop.zoneDamage',t:'num',tip:'tl.tip.zoneDamage'},
    {k:'showBorder',l:'tl.prop.showBorder',t:'bool',tip:'tl.tip.showBorder'},
    {k:'orbitEnabled',l:'tl.prop.orbitEnabled',t:'bool',tip:'tl.tip.orbitEnabled'},
    {k:'orbitRadius',l:'tl.prop.orbitRadius',t:'num'},{k:'orbitSpeed',l:'tl.prop.orbitSpeed',t:'num'}]},
  ENTITY_SPAWN:{desc:"tl.desc.ENTITY_SPAWN",props:[
    {k:'entityCommand',l:'tl.prop.entityCommand',t:'text',tip:'tl.tip.entityCommand'},
    {k:'spawnX',l:'tl.prop.spawnX',t:'num',pos:true},{k:'spawnY',l:'tl.prop.spawnY',t:'num',pos:true},{k:'spawnZ',l:'tl.prop.spawnZ',t:'num',pos:true},
    {k:'relativePos',l:'tl.prop.relativePos',t:'bool',tip:'tl.tip.relativePos'},
    {k:'interval',l:'tl.prop.interval',t:'num',tip:'tl.tip.interval'}]},
  ITEM_DROP:{desc:"tl.desc.ITEM_DROP",props:[
    {k:'itemId',l:'tl.prop.itemId',t:'text',tip:'tl.tip.itemId',dl:'global-items-dl'},
    {k:'itemCount',l:'tl.prop.itemCount',t:'num'},
    {k:'interval',l:'tl.prop.interval',t:'num',tip:'tl.tip.interval'},
    {k:'chancePercent',l:'tl.prop.chancePercent',t:'num',min:0,max:100}]},
  GIVE_ITEM_TO_PLAYER:{desc:"tl.desc.GIVE_ITEM_TO_PLAYER",props:[
    {k:'itemId',l:'tl.prop.itemId',t:'text',tip:'tl.tip.itemId',dl:'global-items-dl'},
    {k:'itemCount',l:'tl.prop.itemCount',t:'num'},
    {k:'chancePercent',l:'tl.prop.chancePercent',t:'num',min:0,max:100,tip:'tl.tip.chancePercent'}]},
  TAKE_ITEM_FROM_PLAYER:{desc:"tl.desc.TAKE_ITEM_FROM_PLAYER",props:[
    {k:'itemId',l:'tl.prop.itemId',t:'text',dl:'global-items-dl'},{k:'itemCount',l:'tl.prop.itemCount',t:'num'}]},
  SPAWN_ITEM_AT_POS:{desc:"tl.desc.SPAWN_ITEM_AT_POS",props:[
    {k:'itemId',l:'tl.prop.itemId',t:'text',dl:'global-items-dl'},{k:'itemCount',l:'tl.prop.itemCount',t:'num'},
    {k:'spawnX',l:'tl.prop.spawnX',t:'num',pos:true},{k:'spawnY',l:'tl.prop.spawnY',t:'num',pos:true},{k:'spawnZ',l:'tl.prop.spawnZ',t:'num',pos:true},
    {k:'relativePos',l:'tl.prop.relativePos',t:'bool',tip:'tl.tip.relativePos'}]},
  FLAG_OVERRIDE:{desc:"tl.desc.FLAG_OVERRIDE",props:[
    {k:'flagId',l:'tl.prop.flagId',t:'text',tip:'tl.tip.flagId'},
    {k:'targetZoneId',l:'tl.prop.targetZoneId',t:'text',tip:'tl.tip.targetZoneId'},
    {k:'flagValue',l:'tl.prop.flagValue',t:'bool'}]},
  ASSIGN_ROLE_PRIVATE:{desc:"tl.desc.ASSIGN_ROLE_PRIVATE",props:[
    {k:'roleName',l:'tl.prop.roleName',t:'text',tip:'tl.tip.roleName'},
    {k:'visibleTo',l:'tl.prop.visibleTo',t:'text',tip:'tl.tip.visibleTo'},
    {k:'description',l:'tl.prop.description',t:'text'}]},
  START_VOTE:{desc:"tl.desc.START_VOTE",props:[
    {k:'question',l:'tl.prop.question',t:'text',tip:'tl.tip.question'},
    {k:'options',l:'tl.prop.options',t:'list',phKey:'tl.optionPlaceholder',tip:'tl.tip.options'},
    {k:'duration',l:'tl.prop.duration',t:'num',tip:'tl.tip.duration'}]},
  SET_PLAYER_HEALTH:{desc:"tl.desc.SET_PLAYER_HEALTH",props:[
    {k:'healthAmount',l:'tl.prop.healthAmount',t:'num',tip:'tl.tip.healthAmount'}]},
  SET_PLAYER_HUNGER:{desc:"tl.desc.SET_PLAYER_HUNGER",props:[
    {k:'hungerLevel',l:'tl.prop.hungerLevel',t:'num',tip:'tl.tip.hungerLevel'}]},
  APPLY_KNOCKBACK:{desc:"tl.desc.APPLY_KNOCKBACK",props:[
    {k:'knockbackPower',l:'tl.prop.knockbackPower',t:'num',tip:'tl.tip.knockbackPower'},
    {k:'direction',l:'tl.prop.direction',t:'select',opts:MC_KNOCKBACK_DIRS,tip:'tl.tip.direction'}]},
  PLAYER_FLAG_SET:{desc:"tl.desc.PLAYER_FLAG_SET",props:[
    {k:'flagId',l:'tl.prop.flagId',t:'text',tip:'tl.tip.flagId'},
    {k:'flagValue',l:'tl.prop.flagValue',t:'bool'}]},
  SET_BLOCK_AT_POS:{desc:"tl.desc.SET_BLOCK_AT_POS",props:[
    {k:'blockType',l:'tl.prop.blockType',t:'text',tip:'tl.tip.blockType'},
    {k:'spawnX',l:'tl.prop.spawnX',t:'num',pos:true},{k:'spawnY',l:'tl.prop.spawnY',t:'num',pos:true},{k:'spawnZ',l:'tl.prop.spawnZ',t:'num',pos:true}]},
  REDSTONE_ACTIVATE:{desc:"tl.desc.REDSTONE_ACTIVATE",props:[
    {k:'targetX',l:'tl.prop.targetX',t:'num',pos:true},{k:'targetY',l:'tl.prop.targetY',t:'num',pos:true},{k:'targetZ',l:'tl.prop.targetZ',t:'num',pos:true}]},
  DEATH_BEHAVIOR_OVERRIDE:{desc:"tl.desc.DEATH_BEHAVIOR_OVERRIDE",props:[
    {k:'deathBehavior',l:'tl.prop.deathBehavior',t:'select',opts:MC_DEATH_BEHAVIORS,tip:'tl.tip.deathBehavior'}]},
  MULTIPLIER:{desc:"tl.desc.MULTIPLIER",props:[
    {k:'multiplierType',l:'tl.prop.multiplierType',t:'select',opts:['xp','damage','healing']},
    {k:'multiplierValue',l:'tl.prop.multiplierValue',t:'num',tip:'tl.tip.multiplierValue'}]},
  SCORE_ADD:{desc:"tl.desc.SCORE_ADD",props:[
    {k:'amount',l:'tl.prop.amount',t:'num',tip:'tl.tip.amount'},
    {k:'target',l:'tl.prop.target',t:'select',opts:['all','killer','victim','random']}]},
  SCORE_SET:{desc:"tl.desc.SCORE_SET",props:[
    {k:'amount',l:'tl.prop.amount',t:'num'},{k:'target',l:'tl.prop.target',t:'select',opts:['all','killer','victim','random']}]},
  SCORE_MULTIPLY:{desc:"tl.desc.SCORE_MULTIPLY",props:[
    {k:'multiplierValue',l:'tl.prop.multiplierValue',t:'num',tip:'tl.tip.multiplierValue'},
    {k:'target',l:'tl.prop.target',t:'select',opts:['all','killer','victim','random']}]},
  RANDOM_EVENT:{desc:"tl.desc.RANDOM_EVENT",props:[
    {k:'eventList',l:'tl.prop.eventList',t:'textarea',ph:'evt_12345\nevt_67890\nevt_abcde:3',tip:'tl.tip.eventList'},
    {k:'chancePercent',l:'tl.prop.chancePercent',t:'num',min:0,max:100,tip:'tl.tip.chancePercent'}]},
  RANDOM_EFFECT:{desc:"tl.desc.RANDOM_EFFECT",props:[
    {k:'effectList',l:'tl.prop.effectList',t:'textarea',ph:'BROADCAST:2\nSOUND\nPOTION:3',tip:'tl.tip.effectList'},
    {k:'chancePercent',l:'tl.prop.chancePercent',t:'num',min:0,max:100}]},
  DIMENSION_TELEPORT:{desc:"tl.desc.DIMENSION_TELEPORT",props:[
    {k:'dimensionId',l:'tl.prop.dimensionId',t:'text',tip:'tl.tip.dimensionId',dl:'global-dims-dl'},
    {k:'spawnX',l:'tl.prop.spawnX',t:'num',pos:true},{k:'spawnY',l:'tl.prop.spawnY',t:'num',pos:true},{k:'spawnZ',l:'tl.prop.spawnZ',t:'num',pos:true}]},
  DIMENSION_CHANGE:{desc:"tl.desc.DIMENSION_CHANGE",props:[
    {k:'dimensionId',l:'tl.prop.dimensionId',t:'text',dl:'global-dims-dl'}]},
  PLAYER_VISIBILITY:{desc:"tl.desc.PLAYER_VISIBILITY",props:[
    {k:'visible',l:'tl.prop.visible',t:'bool'},
    {k:'target',l:'tl.prop.target',t:'select',opts:['all','team','enemies']}]},
  GLOW_EFFECT:{desc:"tl.desc.GLOW_EFFECT",props:[
    {k:'enabled',l:'tl.prop.enabled',t:'bool'},
    {k:'color',l:'tl.prop.color',t:'select',opts:MC_COLORS},
    {k:'target',l:'tl.prop.target',t:'select',opts:['all','team','enemies']}]},
  STAT_MODIFY:{desc:"tl.desc.STAT_MODIFY",props:[
    {k:'statId',l:'tl.prop.statId',t:'text',tip:'tl.tip.statId'},
    {k:'amount',l:'tl.prop.amount',t:'num'},{k:'operation',l:'tl.prop.operation',t:'select',opts:['add','set','multiply']}]},
  STAT_RESET:{desc:"tl.desc.STAT_RESET",props:[
    {k:'statId',l:'tl.prop.statId',t:'text'}]},
  DIMENSION_TELEPORT_ALL:{desc:"tl.desc.DIMENSION_TELEPORT_ALL",props:[
    {k:'dimensionId',l:'tl.prop.dimensionId',t:'text',dl:'global-dims-dl'},
    {k:'spawnX',l:'tl.prop.spawnX',t:'num',pos:true},{k:'spawnY',l:'tl.prop.spawnY',t:'num',pos:true},{k:'spawnZ',l:'tl.prop.spawnZ',t:'num',pos:true}]},
  TELEPORT_PLAYER_TO_PLAYER:{desc:"tl.desc.TELEPORT_PLAYER_TO_PLAYER",props:[
    {k:'sourcePlayer',l:'tl.prop.sourcePlayer',t:'text',tip:'tl.tip.sourcePlayer'},
    {k:'targetPlayer',l:'tl.prop.targetPlayer',t:'text'}]},
  POKEMON_SPAWN:{desc:"tl.desc.POKEMON_SPAWN",props:[
    {k:'species',l:'tl.prop.species',t:'species',tip:'tl.tip.species',dl:'global-species-dl'},
    {k:'level',l:'tl.prop.level',t:'num',min:1,max:100},
    {k:'spawnX',l:'tl.prop.spawnX',t:'num',pos:true},{k:'spawnY',l:'tl.prop.spawnY',t:'num',pos:true},{k:'spawnZ',l:'tl.prop.spawnZ',t:'num',pos:true}]},
  SET_PLAYER_VISIBILITY:{desc:"tl.desc.SET_PLAYER_VISIBILITY",props:[
    {k:'visibility',l:'tl.prop.visibility',t:'bool'}]},
  OBJECTIVE_HUD_UPDATE:{desc:"tl.desc.OBJECTIVE_HUD_UPDATE",props:[
    {k:'objectiveText',l:'tl.prop.objectiveText',t:'text'},
    {k:'progress',l:'tl.prop.progress',t:'num',min:0,max:100,tip:'tl.tip.progress'}]},
  INVENTORY_DISTRIBUTE:{desc:"tl.desc.INVENTORY_DISTRIBUTE",props:[
    {k:'itemId',l:'tl.prop.itemId',t:'text',dl:'global-items-dl'},{k:'itemCount',l:'tl.prop.itemCount',t:'num'}]},
  TRANSFER_ROLE:{desc:"tl.desc.TRANSFER_ROLE",props:[
    {k:'fromRole',l:'tl.prop.fromRole',t:'text'},{k:'toRole',l:'tl.prop.toRole',t:'text'}]},
  CHANGE_TEAM_COLOR:{desc:"tl.desc.CHANGE_TEAM_COLOR",props:[
    {k:'newColor',l:'tl.prop.newColor',t:'select',opts:MC_COLORS}]},
  ZONE_LOOT:{desc:"tl.desc.ZONE_LOOT",props:[
    {k:'zoneCenterX',l:'tl.prop.zoneCenterX',t:'num',pos:true},{k:'zoneCenterZ',l:'tl.prop.zoneCenterZ',t:'num',pos:true},
    {k:'zoneRadius',l:'tl.prop.zoneRadius',t:'num'},{k:'lootTable',l:'tl.prop.lootTable',t:'text',tip:'tl.tip.lootTable'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num'}]},
  ZONE_MOVE:{desc:"tl.desc.ZONE_MOVE",props:[
    {k:'zoneCenterX',l:'tl.prop.zoneCenterX',t:'num',pos:true},{k:'zoneCenterZ',l:'tl.prop.zoneCenterZ',t:'num',pos:true},
    {k:'zoneRadius',l:'tl.prop.zoneRadius',t:'num'},{k:'targetX',l:'tl.prop.targetX',t:'num',pos:true},{k:'targetZ',l:'tl.prop.targetZ',t:'num',pos:true},
    {k:'speedBps',l:'tl.prop.speedBps',t:'num',tip:'tl.tip.speedBps'}]},
  ZONE_HEAL:{desc:"tl.desc.ZONE_HEAL",props:[
    {k:'zoneCenterX',l:'tl.prop.zoneCenterX',t:'num',pos:true},{k:'zoneCenterZ',l:'tl.prop.zoneCenterZ',t:'num',pos:true},
    {k:'zoneRadius',l:'tl.prop.zoneRadius',t:'num'},{k:'healAmount',l:'tl.prop.healAmount',t:'num',tip:'tl.tip.healAmount'}]},
  ZONE_DAMAGE:{desc:"tl.desc.ZONE_DAMAGE",props:[
    {k:'zoneCenterX',l:'tl.prop.zoneCenterX',t:'num',pos:true},{k:'zoneCenterZ',l:'tl.prop.zoneCenterZ',t:'num',pos:true},
    {k:'zoneRadius',l:'tl.prop.zoneRadius',t:'num'},{k:'zoneDamage',l:'tl.prop.zoneDamage',t:'num',tip:'tl.tip.zoneDamage'}]},
  ZONE_POWERUP:{desc:"tl.desc.ZONE_POWERUP",props:[
    {k:'powerX',l:'tl.prop.powerX',t:'num',pos:true,tip:'tl.tip.powerX'},
    {k:'powerY',l:'tl.prop.powerY',t:'num',pos:true,tip:'tl.tip.powerY'},
    {k:'powerZ',l:'tl.prop.powerZ',t:'num',pos:true,tip:'tl.tip.powerZ'},
    {k:'powerWorld',l:'common.world',t:'text',dl:'global-dims-dl',tip:'tl.tip.powerWorld'},
    {k:'powerRadius',l:'tl.prop.powerRadius',t:'num',tip:'tl.tip.powerRadius'},
    {k:'powerType',l:'tl.prop.powerType',t:'select',opts:[
      {v:'SPEED',l:'tl.powerup.speed'},{v:'JUMP_BOOST',l:'tl.powerup.jump_boost'},{v:'STRENGTH',l:'tl.powerup.strength'},
      {v:'RESISTANCE',l:'tl.powerup.resistance'},{v:'REGENERATION',l:'tl.powerup.regeneration'},
      {v:'HASTE',l:'tl.powerup.haste'},{v:'SLOW_FALLING',l:'tl.powerup.slow_falling'}
    ],tip:'tl.tip.powerType'},
    {k:'powerAmplifier',l:'tl.prop.powerAmplifier',t:'num',min:0,max:9,tip:'tl.tip.powerAmplifier'},
    {k:'powerDurationTicks',l:'tl.prop.powerDurationTicks',t:'num',tip:'tl.tip.powerDurationTicks'},
    {k:'singleUse',l:'tl.prop.singleUse',t:'bool',tip:'tl.tip.singleUse'},
    {k:'cooldownSeconds',l:'tl.prop.cooldownSeconds',t:'num',min:0,tip:'tl.tip.cooldownSeconds'},
    {k:'visualBlock',l:'tl.prop.visualBlock',t:'select',opts:[
      {v:'minecraft:end_rod',l:'tl.block.endRod'},{v:'minecraft:sea_lantern',l:'tl.block.seaLantern'},
      {v:'minecraft:beacon',l:'tl.block.beacon'},{v:'minecraft:glowstone',l:'tl.block.glowstone'},
      {v:'minecraft:shroomlight',l:'tl.block.shroomlight'},{v:'minecraft:lantern',l:'tl.block.lantern'}
    ],tip:'tl.tip.visualBlock'},
    {k:'powerLabel',l:'tl.prop.powerLabel',t:'text',tip:'tl.tip.powerLabel'}]},
  RESPAWN_ENTITY_AT_POS:{desc:"tl.desc.RESPAWN_ENTITY_AT_POS",props:[
    {k:'entityType',l:'tl.prop.entityType',t:'text',tip:'tl.tip.entityType'},
    {k:'spawnX',l:'tl.prop.spawnX',t:'num',pos:true},{k:'spawnY',l:'tl.prop.spawnY',t:'num',pos:true},{k:'spawnZ',l:'tl.prop.spawnZ',t:'num',pos:true}]},
  SPAWN_STRUCTURE:{desc:"tl.desc.SPAWN_STRUCTURE",props:[
    {k:'structureName',l:'tl.prop.structureName',t:'text',tip:'tl.tip.structureName'},
    {k:'rotation',l:'tl.prop.rotation',t:'select',opts:['NONE','CLOCKWISE_90','COUNTERCLOCKWISE_90','180']},
    {k:'offsetX',l:'tl.prop.offsetX',t:'num'},{k:'offsetY',l:'tl.prop.offsetY',t:'num'},{k:'offsetZ',l:'tl.prop.offsetZ',t:'num'}]},
  RANDOM_LOOT_AT_POSITIONS:{desc:"tl.desc.RANDOM_LOOT_AT_POSITIONS",props:[
    {k:'positions',l:'tl.prop.positions',t:'textarea',ph:'[{"x":0,"y":64,"z":0}]',tip:"Liste JSON de {x, y, z}"},
    {k:'lootTable',l:'tl.prop.lootTable',t:'text'},
    {k:'chancePercent',l:'tl.prop.chancePercent',t:'num',min:0,max:100}]},
  WEIGHTED_RANDOM_COMMAND:{desc:"tl.desc.WEIGHTED_RANDOM_COMMAND",props:[
    {k:'commands',l:'tl.prop.commands',t:'textarea',ph:'[{"cmd":"say Hi","weight":1}]',tip:'tl.tip.commands'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num'}]},
  COBBLEMON_FORCE_ENCOUNTER:{desc:"tl.desc.COBBLEMON_FORCE_ENCOUNTER",props:[
    {k:'species',l:'tl.prop.species',t:'species',tip:'tl.tip.species',dl:'global-species-dl'},
    {k:'levelMin',l:'tl.prop.levelMin',t:'num',min:1,max:100},{k:'levelMax',l:'tl.prop.levelMax',t:'num',min:1,max:100},
    {k:'shinyChance',l:'tl.prop.shinyChance',t:'num',min:0,max:100,step:0.1}]},
  CONDITIONAL_BRANCH:{desc:"tl.desc.CONDITIONAL_BRANCH",props:[
    {k:'conditionLabel',l:'tl.prop.conditionLabel',t:'text',tip:'tl.tip.conditionLabel'},
    {k:'thenBlocks',l:'tl.prop.thenBlocks',t:'textarea',ph:'[{"effectType":"COMMAND","props":{"command":"say Oui"}}]'},
    {k:'elseBlocks',l:'tl.prop.elseBlocks',t:'textarea',ph:'[{"effectType":"COMMAND","props":{"command":"say Non"}}]'}]},
  BROADCAST_SCORE_BOARD:{desc:"tl.desc.BROADCAST_SCORE_BOARD",props:[
    {k:'format',l:'tl.prop.format',t:'text',ph:'{player}: {score}',tip:"Variables: {player}, {score}, {rank}"},
    {k:'title',l:'tl.prop.title',t:'text'},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num'}]},
  STATS_BROADCAST:{desc:"tl.desc.STATS_BROADCAST",props:[
    {k:'statsFilter',l:'tl.prop.statsFilter',t:'text',ph:'kills,deaths,score',tip:'tl.tip.statsFilter'},
    {k:'format',l:'tl.prop.format',t:'select',opts:['table','list','compact']},
    {k:'intervalSeconds',l:'tl.prop.intervalSeconds',t:'num'}]},
  FREEZE_PLAYERS:{desc:"tl.desc.FREEZE_PLAYERS",props:[]},
  UNFREEZE_PLAYERS:{desc:"tl.desc.UNFREEZE_PLAYERS",props:[]},
  LOCK_HOTBAR:{desc:"tl.desc.LOCK_HOTBAR",props:[]},
  UNLOCK_HOTBAR:{desc:"tl.desc.UNLOCK_HOTBAR",props:[]},
  TRANSFORM_ALL:{desc:"tl.desc.TRANSFORM_ALL",props:[
    {k:'transformSpecies',l:'tl.prop.transformSpecies',t:'species',tip:'tl.tip.transformSpecies',dl:'global-species-dl'}]},
  TRANSFORM_RANDOM:{desc:"tl.desc.TRANSFORM_RANDOM",props:[
    {k:'transformSpeciesList',l:'tl.prop.transformSpeciesList',t:'text',tip:'tl.tip.transformSpeciesList',dl:'global-species-dl'},
    {k:'allowDuplicates',l:'tl.prop.allowDuplicates',t:'bool',tip:'tl.tip.allowDuplicates'}]},
  TRANSFORM_BY_TEAM:{desc:"tl.desc.TRANSFORM_BY_TEAM",props:[
    {k:'transformTeamMap',l:'tl.prop.transformTeamMap',t:'textarea',ph:'{"0":"charizard","1":"blastoise"}',tip:'tl.tip.transformTeamMap'}]},
  TRANSFORM_REVERT_ALL:{desc:"tl.desc.TRANSFORM_REVERT_ALL",props:[]},
  DUNGEON_OPEN:{desc:"tl.desc.DUNGEON_OPEN",props:[
    {k:'dungeonId',l:'tl.prop.dungeonId',t:'text',tip:'tl.tip.dungeonId'},
    {k:'dungeonTier',l:'tl.prop.dungeonTier',t:'num',min:1,max:10,tip:'tl.tip.dungeonTier'}]},
  DUNGEON_MODIFIER:{desc:"tl.desc.DUNGEON_MODIFIER",props:[
    {k:'dungeonId',l:'tl.prop.dungeonId',t:'text',tip:'tl.tip.dungeonId'},
    {k:'modifierType',l:'tl.prop.modifierType',t:'select',opts:['DOUBLE_LOOT','HALF_TIMER','FREE_TIER'],tip:'tl.tip.modifierType'},
    {k:'modifierValue',l:'tl.prop.modifierValue',t:'num',tip:'tl.tip.modifierValue'}]},
  DUNGEON_TICKET_GIVE:{desc:"tl.desc.DUNGEON_TICKET_GIVE",props:[
    {k:'dungeonId',l:'tl.prop.dungeonId',t:'text',tip:'tl.tip.dungeonId'},
    {k:'dungeonTier',l:'tl.prop.dungeonTier',t:'num',min:1,max:10,tip:'tl.tip.dungeonTier'},
    {k:'ticketCount',l:'tl.prop.ticketCount',t:'num',min:1,tip:'tl.tip.ticketCount'}]},
  // ── CheeseKart ──────────────────────────────────────────────────────────
  KART_START_ALL:{desc:"tl.desc.KART_START_ALL",props:[
    {k:'kartPresetId',l:'tl.prop.kartPresetId',t:'text',tip:'tl.tip.kartPresetId'},
    {k:'kartSpeciesOverride',l:'tl.prop.kartSpeciesOverride',t:'species',tip:'tl.tip.kartSpeciesOverride',dl:'global-species-dl'}]},
  KART_STOP_ALL:{desc:"tl.desc.KART_STOP_ALL",props:[]},
};

// ── Effects per track (maps track ID → available effect types) ──────────────
const TL_EFFECTS = {
  messages:    [{t:'BROADCAST',l:'tl.desc.BROADCAST'},{t:'ACTIONBAR_PERSISTENT',l:'tl.desc.ACTIONBAR_PERSISTENT'},{t:'TITLE',l:'tl.desc.TITLE'},{t:'BROADCAST_SCORE_BOARD',l:'tl.desc.BROADCAST_SCORE_BOARD'},{t:'STATS_BROADCAST',l:'tl.desc.STATS_BROADCAST'}],
  potions:     [{t:'POTION',l:'tl.desc.POTION'}],
  zones:       [{t:'ZONE_SHRINK',l:'tl.desc.ZONE_SHRINK'},{t:'ZONE_HEAL',l:'tl.desc.ZONE_HEAL'},{t:'ZONE_DAMAGE',l:'tl.desc.ZONE_DAMAGE'},{t:'ZONE_LOOT',l:'tl.desc.ZONE_LOOT'},{t:'ZONE_MOVE',l:'tl.desc.ZONE_MOVE'},{t:'ZONE_POWERUP',l:'tl.desc.ZONE_POWERUP'}],
  spawns:      [{t:'ENTITY_SPAWN',l:'tl.desc.ENTITY_SPAWN'},{t:'POKEMON_SPAWN',l:'tl.desc.POKEMON_SPAWN'},{t:'COBBLEMON_FORCE_ENCOUNTER',l:'tl.desc.COBBLEMON_FORCE_ENCOUNTER'},{t:'RESPAWN_ENTITY_AT_POS',l:'tl.desc.RESPAWN_ENTITY_AT_POS'},{t:'SPAWN_STRUCTURE',l:'tl.desc.SPAWN_STRUCTURE'},{t:'RANDOM_LOOT_AT_POSITIONS',l:'tl.desc.RANDOM_LOOT_AT_POSITIONS'}],
  commands:    [{t:'COMMAND',l:'tl.desc.COMMAND'},{t:'COMMAND_PLAYER',l:'tl.desc.COMMAND_PLAYER'},{t:'WEIGHTED_RANDOM_COMMAND',l:'tl.desc.WEIGHTED_RANDOM_COMMAND'},{t:'DUNGEON_OPEN',l:'tl.desc.DUNGEON_OPEN'}],
  weather:     [{t:'WEATHER',l:'tl.desc.WEATHER'},{t:'TIME',l:'tl.desc.TIME'}],
  sounds:      [{t:'SOUND',l:'tl.desc.SOUND'},{t:'PARTICLE',l:'tl.desc.PARTICLE'}],
  score:       [{t:'SCORE_ADD',l:'tl.desc.SCORE_ADD'},{t:'SCORE_SET',l:'tl.desc.SCORE_SET'},{t:'SCORE_MULTIPLY',l:'tl.desc.SCORE_MULTIPLY'}],
  flags:       [{t:'FLAG_OVERRIDE',l:'tl.desc.FLAG_OVERRIDE'},{t:'DUNGEON_MODIFIER',l:'tl.desc.DUNGEON_MODIFIER'}],
  roles:       [{t:'ASSIGN_ROLE_PRIVATE',l:'tl.desc.ASSIGN_ROLE_PRIVATE'},{t:'TRANSFER_ROLE',l:'tl.desc.TRANSFER_ROLE'},{t:'CHANGE_TEAM_COLOR',l:'tl.desc.CHANGE_TEAM_COLOR'}],
  votes:       [{t:'START_VOTE',l:'tl.desc.START_VOTE'}],
  players:     [{t:'FREEZE_PLAYERS',l:'tl.desc.FREEZE_PLAYERS'},{t:'UNFREEZE_PLAYERS',l:'tl.desc.UNFREEZE_PLAYERS'},{t:'SET_PLAYER_HEALTH',l:'tl.desc.SET_PLAYER_HEALTH'},{t:'SET_PLAYER_HUNGER',l:'tl.desc.SET_PLAYER_HUNGER'},{t:'SET_PLAYER_VISIBILITY',l:'tl.desc.SET_PLAYER_VISIBILITY'},{t:'APPLY_KNOCKBACK',l:'tl.desc.APPLY_KNOCKBACK'},{t:'LOCK_HOTBAR',l:'tl.desc.LOCK_HOTBAR'},{t:'UNLOCK_HOTBAR',l:'tl.desc.UNLOCK_HOTBAR'},{t:'TELEPORT_PLAYER_TO_PLAYER',l:'tl.desc.TELEPORT_PLAYER_TO_PLAYER'},{t:'DIMENSION_TELEPORT_ALL',l:'tl.desc.DIMENSION_TELEPORT_ALL'},{t:'TRANSFORM_ALL',l:'tl.desc.TRANSFORM_ALL'},{t:'TRANSFORM_RANDOM',l:'tl.desc.TRANSFORM_RANDOM'},{t:'TRANSFORM_BY_TEAM',l:'tl.desc.TRANSFORM_BY_TEAM'},{t:'TRANSFORM_REVERT_ALL',l:'tl.desc.TRANSFORM_REVERT_ALL'},{t:'KART_START_ALL',l:'tl.desc.KART_START_ALL'},{t:'KART_STOP_ALL',l:'tl.desc.KART_STOP_ALL'}],
  state_flags: [{t:'PLAYER_FLAG_SET',l:'tl.desc.PLAYER_FLAG_SET'}],
  random:      [{t:'RANDOM_EVENT',l:'tl.desc.RANDOM_EVENT'},{t:'RANDOM_EFFECT',l:'tl.desc.RANDOM_EFFECT'}],
  objectives:  [{t:'OBJECTIVE_HUD_UPDATE',l:'tl.desc.OBJECTIVE_HUD_UPDATE'}],
  death:       [{t:'DEATH_BEHAVIOR_OVERRIDE',l:'tl.desc.DEATH_BEHAVIOR_OVERRIDE'}],
  dimension:   [{t:'DIMENSION_TELEPORT',l:'tl.desc.DIMENSION_TELEPORT'},{t:'DIMENSION_CHANGE',l:'tl.desc.DIMENSION_CHANGE'}],
  inventory:   [{t:'ITEM_DROP',l:'tl.desc.ITEM_DROP'},{t:'GIVE_ITEM_TO_PLAYER',l:'tl.desc.GIVE_ITEM_TO_PLAYER'},{t:'TAKE_ITEM_FROM_PLAYER',l:'tl.desc.TAKE_ITEM_FROM_PLAYER'},{t:'SPAWN_ITEM_AT_POS',l:'tl.desc.SPAWN_ITEM_AT_POS'},{t:'INVENTORY_DISTRIBUTE',l:'tl.desc.INVENTORY_DISTRIBUTE'},{t:'DUNGEON_TICKET_GIVE',l:'tl.desc.DUNGEON_TICKET_GIVE'}],
  visibility:  [{t:'PLAYER_VISIBILITY',l:'tl.desc.PLAYER_VISIBILITY'},{t:'GLOW_EFFECT',l:'tl.desc.GLOW_EFFECT'}],
  redstone:    [{t:'REDSTONE_ACTIVATE',l:'tl.desc.REDSTONE_ACTIVATE'},{t:'SET_BLOCK_AT_POS',l:'tl.desc.SET_BLOCK_AT_POS'}],
  stats:       [{t:'STAT_MODIFY',l:'tl.desc.STAT_MODIFY'},{t:'STAT_RESET',l:'tl.desc.STAT_RESET'}],
  logic:       [{t:'CONDITIONAL_BRANCH',l:'tl.desc.CONDITIONAL_BRANCH'},{t:'MULTIPLIER',l:'tl.desc.MULTIPLIER'}],
};

function _tlGenId() {
  return Math.random().toString(36).substring(2, 10);
}

async function buildTimeline(app, eventId) {
  let evt;
  try {
    evt = await API.events.get(eventId);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${t('common.error')}: ${escHtml(e.message)}</span></div>`;
  }

  // Initialize state
  const _tl = window._tl = {
    eventId, eventName: evt.name, app,
    acts: evt.acts && evt.acts.length > 0 ? JSON.parse(JSON.stringify(evt.acts)) : [{ id: _tlGenId(), name: t('tl.defaultAct'), description: '', order: 0, transitionType: 'DURATION', duration: 300, blocks: [] }],
    selectedActIndex: 0,
    selectedBlockId: null,
    dirty: false,
    zoom: 4.0,
    scrollX: 0,
    snapInterval: 10,
    trackLanes: {},  // { messages: 1, potions: 1, ... } — lane count per track type
    dragMode: 'NONE',
    dragBlockId: null, dragStartMouseX: 0, dragOrigStart: 0, dragOrigDuration: 0,
    _autoSaveTimer: null,
    _undoStack: [],
    _clipboard: null,
  };

  // Compute lane counts from existing block data
  function _tl_computeTrackLanes() {
    const lanes = {};
    TL_TRACKS.forEach(t => lanes[t.id] = 1);
    const act = _tl.acts[_tl.selectedActIndex] || _tl.acts[0];
    (act.blocks || []).forEach(b => {
      const lane = b.lane || 0;
      if (lanes[b.track] !== undefined) {
        lanes[b.track] = Math.max(lanes[b.track], lane + 1);
      }
    });
    _tl.trackLanes = lanes;
  }
  _tl_computeTrackLanes();

  // Start auto-save
  _tl._autoSaveTimer = setInterval(() => { if (_tl.dirty) _tlSave(); }, 5000);

  // Cleanup on navigate away
  const origNav = app.navigate.bind(app);
  const cleanup = () => {
    if (_tl._autoSaveTimer) clearInterval(_tl._autoSaveTimer);
    document.removeEventListener('keydown', _tlKeyHandler);
  };

  function _tlSnap(val) {
    if (_tl.snapInterval <= 0) return Math.max(0, val);
    return Math.max(0, Math.round(val / _tl.snapInterval) * _tl.snapInterval);
  }

  function _tlPushUndo() {
    _tl._undoStack.push(JSON.stringify(_tl.acts));
    if (_tl._undoStack.length > 50) _tl._undoStack.shift();
  }

  function _tlUndo() {
    if (_tl._undoStack.length === 0) { app.toast(t('common.error'), 'warn'); return; }
    const snapshot = _tl._undoStack.pop();
    _tl.acts = JSON.parse(snapshot);
    if (_tl.selectedActIndex >= _tl.acts.length) _tl.selectedActIndex = _tl.acts.length - 1;
    _tl.selectedBlockId = null;
    _tl.dirty = true;
    const el = document.getElementById('tl-dirty');
    if (el) el.style.display = '';
    _tl_computeTrackLanes();
    renderAll();
    app.toast(t('common.success'), 'success');
  }

  function _tlMarkDirty() {
    _tl.dirty = true;
    const el = document.getElementById('tl-dirty');
    if (el) el.style.display = '';
  }

  function _tlValidate() {
    const errors = [];
    _tl.acts.forEach((act, ai) => {
      (act.blocks || []).forEach((b, bi) => {
        const prefix = t('tl.validate.prefix', {act: ai+1, block: bi+1});
        if (!b.effectType) errors.push(`${prefix}: ${t('tl.validate.missingEffect')}`);
        if (b.start < 0) errors.push(`${prefix}: ${t('tl.validate.negativeStart')} (${b.start})`);
        if (b.duration < 0) errors.push(`${prefix}: ${t('tl.validate.negativeDuration')} (${b.duration}s)`);
      });
    });
    return errors;
  }

  async function _tlSave() {
    const errors = _tlValidate();
    if (errors.length > 0) {
      app.toast(t('tl.validation') + errors[0] + (errors.length > 1 ? ` (+${errors.length - 1})` : ''), 'error');
      return;
    }
    try {
      await API.events.updateActs(_tl.eventId, _tl.acts);
      _tl.dirty = false;
      const el = document.getElementById('tl-dirty');
      if (el) el.style.display = 'none';
      app.toast(t('common.success'), 'success');
    } catch (e) {
      app.toast(t('error.generic', {msg: e.message}), 'error');
    }
  }

  function _tlGetAct() {
    return _tl.acts[_tl.selectedActIndex] || _tl.acts[0];
  }

  function _tlGetBlock(id) {
    const act = _tlGetAct();
    return act.blocks.find(b => b.id === id);
  }

  function _tlGetSelectedBlock() {
    return _tl.selectedBlockId ? _tlGetBlock(_tl.selectedBlockId) : null;
  }

  // ── Render functions ───────────────────────────────────────────────────
  function renderActs() {
    const el = document.getElementById('tl-acts');
    if (!el) return;
    let html = '';
    _tl.acts.forEach((act, i) => {
      const dur = act.duration || 0;
      const m = Math.floor(dur / 60), s = dur % 60;
      html += `<div class="tl-act-item ${i === _tl.selectedActIndex ? 'active' : ''}" onclick="_tl_selectAct(${i})">
        <div class="flex items-center gap-1">
          <span id="tl-act-name-${i}" class="text-xs font-mono text-tx flex-1 truncate">${escHtml(act.name || t('tl.actDefault', {n: i+1}))}</span>
          <button class="text-muted hover:text-tx text-xs" onclick="event.stopPropagation();_tl_renameAct(${i})" title="${t('common.rename')}">✎</button>
          ${_tl.acts.length > 1 ? `<button class="text-muted hover:text-red text-xs" onclick="event.stopPropagation();_tl_deleteAct(${i})" title="${t('common.delete')}">✕</button>` : ''}
        </div>
        <div class="text-xs font-mono text-muted mt-0.5"><span class="cursor-pointer hover:text-amber" onclick="event.stopPropagation();_tl_editActDuration(${i})" title="${t('common.edit')}">${m}m${s > 0 ? String(s).padStart(2,'0') + 's' : ''}</span> \u00b7 ${(act.blocks||[]).length} ${t('tl.blocks')}</div>
      </div>`;
    });
    html += `<button class="w-full px-3 py-2 text-xs font-mono text-amber hover-elevated transition-all text-center" onclick="_tl_addAct()">${t('common.add')}</button>`;
    // Total duration
    const totalDur = _tl.acts.reduce((s, a) => s + (a.duration || 0), 0);
    const tm = Math.floor(totalDur / 60), ts = totalDur % 60;
    html += `<div class="px-3 py-2 text-xs font-mono text-muted border-t" style="border-color:var(--bg-border)">${t('tl.total')} ${tm}m${ts > 0 ? String(ts).padStart(2,'0') + 's' : ''}</div>`;
    el.innerHTML = html;
  }

  function renderRuler() {
    const el = document.getElementById('tl-ruler');
    if (!el) return;
    const act = _tlGetAct();
    const dur = act.duration || 300;
    const pps = _tl.zoom;
    const totalWidth = dur * pps;
    const container = document.getElementById('tl-tracks-scroller');
    const scrollLeft = container ? container.scrollLeft : 0;
    const viewWidth = container ? container.clientWidth - 140 : 800;
    let html = '';
    // Determine step size
    let step = 10;
    if (pps < 1) step = 60;
    else if (pps < 2) step = 30;
    else if (pps < 4) step = 10;
    else if (pps < 8) step = 5;
    else step = 5;
    const startT = Math.floor(scrollLeft / pps / step) * step;
    const endT = Math.ceil((scrollLeft + viewWidth) / pps / step) * step;
    for (let t = startT; t <= Math.min(endT, dur); t += step) {
      const x = t * pps - scrollLeft + 140;
      if (x < 130 || x > viewWidth + 150) continue;
      const m = Math.floor(t / 60), s = t % 60;
      const label = t >= 60 ? `${m}:${String(s).padStart(2,'0')}` : `${t}s`;
      html += `<div style="position:absolute;left:${x}px;top:0;height:100%;border-left:1px solid var(--border-track);display:flex;align-items:center;padding-left:4px">
        <span style="font-family:'Geist Mono',monospace;font-size:0.7rem;color:var(--muted)">${label}</span>
      </div>`;
    }
    el.innerHTML = html;
  }

  function renderTracks() {
    const el = document.getElementById('tl-tracks');
    if (!el) return;
    const act = _tlGetAct();
    const dur = act.duration || 300;
    const pps = _tl.zoom;
    const totalWidth = dur * pps;
    let html = '';
    TL_TRACKS.forEach(track => {
      const hasEffects = (TL_EFFECTS[track.id] || []).length > 0;
      const laneCount = _tl.trackLanes[track.id] || 1;
      for (let li = 0; li < laneCount; li++) {
        const isFirst = li === 0;
        const isLast = li === laneCount - 1;
        const classes = ['tl-track'];
        if (!hasEffects) classes.push('tl-track-disabled');
        if (isLast) classes.push('tl-lane-last');
        if (!isFirst) classes.push('tl-lane-continuation');
        html += `<div class="${classes.join(' ')}" data-track="${track.id}" data-lane="${li}">`;
        // Label
        if (isFirst) {
          html += `<div class="tl-track-label" ${!hasEffects ? 'title=""' : ''}>
            <span class="tl-track-icon">${track.icon}</span>
            <span class="tl-track-name truncate">${t(track.label)}</span>
            ${!hasEffects ? '<span class="text-muted" style="font-size:0.5rem;margin-left:2px">\u2014</span>' : `<button class="tl-lane-add" onclick="event.stopPropagation();_tl_addLane('${track.id}')" title="${t('common.add')}">+</button>`}
          </div>`;
        } else {
          // Continuation lane label: colored bar + lane number + optional remove button
          const lastLaneEmpty = !((act.blocks || []).some(b => b.track === track.id && (b.lane || 0) === li));
          html += `<div class="tl-track-label tl-lane-continuation" style="border-left:3px solid ${track.color}">
            <span class="tl-lane-num">${li + 1}</span>
            ${isLast ? `<button class="tl-lane-remove" onclick="event.stopPropagation();_tl_removeLane('${track.id}')" title="${t('common.remove')}">\u2212</button>` : ''}
          </div>`;
        }
        // Track content with blocks for this lane
        html += `<div class="tl-track-content" style="min-width:${totalWidth}px" data-track-content="${track.id}" data-lane-content="${li}"
          ondblclick="_tl_createBlock(event,'${track.id}',${li})">`;
        (act.blocks || []).filter(b => b.track === track.id && (b.lane || 0) === li).forEach(block => {
          const x = block.start * pps;
          const w = Math.max(6, block.duration * pps);
          const bgColor = block.color || track.color;
          const selected = block.id === _tl.selectedBlockId;
          const _effKey = TL_EFFECTS[track.id]?.find(e => e.t === block.effectType)?.l;
          const label = block.label || (_effKey ? t(_effKey) : block.effectType || '');
          html += `<div class="tl-block ${selected ? 'selected' : ''}" id="blk-${block.id}"
            style="left:${x}px;width:${w}px;background:${bgColor}"
            data-block-id="${block.id}"
            onmousedown="_tl_blockMouseDown(event,'${block.id}')">
            <div class="tl-resize-handle left" onmousedown="_tl_resizeMouseDown(event,'${block.id}','left')"></div>
            ${w > 20 ? `<span style="pointer-events:none;overflow:hidden;text-overflow:ellipsis">${escHtml(label)}</span>` : ''}
            <div class="tl-resize-handle right" onmousedown="_tl_resizeMouseDown(event,'${block.id}','right')"></div>
          </div>`;
        });
        html += `</div></div>`;
      }
    });
    el.innerHTML = html;
  }

  function _tl_tipHtml(tip) {
    if (!tip) return '';
    return ` <span class="tl-tip" data-tip="${escHtml(tip)}">(?)</span>`;
  }

  function renderProps() {
    const el = document.getElementById('tl-props');
    if (!el) return;
    const block = _tlGetSelectedBlock();
    if (!block) {
      el.innerHTML = '<div class="text-sm text-muted text-center py-8 font-mono">' + t('common.select') + '</div>';
      return;
    }
    const track = TL_TRACKS.find(t => t.id === block.track);
    const effects = TL_EFFECTS[block.track] || [];
    let html = '';
    // Track info + lane
    html += `<div class="flex items-center gap-2 mb-3 pb-2" style="border-bottom:1px solid var(--bg-border)">
      <span>${track?.icon || '?'}</span>
      <span class="text-xs font-mono text-muted-light flex-1">${track?.label ? t(track.label) : block.track}</span>
      <span class="text-xs font-mono text-muted" title="${t('tl.lane')}">L${(block.lane || 0) + 1}</span>
      <input type="number" class="tl-prop-input" style="width:2.5rem;padding:2px 4px;text-align:center;font-size:var(--type-2xs)" value="${(block.lane || 0) + 1}" min="1" max="${_tl.trackLanes[block.track] || 1}" title="${t('tl.changeLane')}"
        onchange="const v=Math.max(0,Number(this.value)-1);_tl_setBlockLane(v)">
    </div>`;
    // Effect type
    html += `<div class="tl-prop-label">${t('events.effectType')}</div>`;
    html += `<select class="tl-prop-select" onchange="_tl_setProp('effectType',this.value);renderProps()">`;
    effects.forEach(e => {
      html += `<option value="${e.t}" ${block.effectType === e.t ? 'selected' : ''}>${escHtml(t(e.l))}</option>`;
    });
    if (!effects.find(e => e.t === block.effectType) && block.effectType) {
      html += `<option value="${escHtml(block.effectType)}" selected>${escHtml(block.effectType)}</option>`;
    }
    html += `</select>`;
    // Effect description
    const epDef = TL_EFFECT_PROPS[block.effectType];
    const effectDesc = epDef?.desc;
    if (effectDesc) {
      html += `<div class="tl-effect-desc">${escHtml(t(effectDesc))}</div>`;
    }
    // Start & Duration
    html += `<div class="flex gap-2 mt-2">`;
    html += `<div class="flex-1"><div class="tl-prop-label">${t('common.start')} (s)</div><input type="number" class="tl-prop-input" value="${block.start}" min="0" onchange="_tl_setProp('start',Number(this.value))"></div>`;
    html += `<div class="flex-1"><div class="tl-prop-label">${t('common.duration')} (s)</div><input type="number" class="tl-prop-input" value="${block.duration}" min="5" onchange="_tl_setProp('duration',Math.max(5,Number(this.value)))"></div>`;
    html += `</div>`;
    // Repeat
    html += `<div class="tl-prop-label">${t('tl.repeat')}</div>`;
    html += `<input type="number" class="tl-prop-input" value="${block.repeat || 0}" min="0" onchange="_tl_setProp('repeat',Number(this.value))">`;
    // Label
    html += `<div class="tl-prop-label">${t('tl.labelOptional')}</div>`;
    html += `<input type="text" class="tl-prop-input" value="${escHtml(block.label || '')}" onchange="_tl_setProp('label',this.value)">`;
    // Color
    html += `<div class="tl-prop-label">${t('tl.colorOptional')}</div>`;
    html += `<div class="flex gap-2 items-center">
      <input type="color" value="${block.color || track?.color || '#888888'}" style="width:28px;height:24px;border:none;background:none;cursor:pointer" onchange="_tl_setProp('color',this.value)">
      <button class="text-xs text-muted hover:text-tx" onclick="_tl_setProp('color','')">${t('common.reset')}</button>
    </div>`;
    // Effect-specific properties
    const propDefs = epDef?.props || (Array.isArray(epDef) ? epDef : []);
    if (propDefs.length > 0) {
      html += `<div class="mt-3 pt-2" style="border-top:1px solid var(--bg-border)">
        <div class="text-xs font-mono text-amber mb-2">${t('tl.effectProperties')}</div>`;
      propDefs.forEach(pd => {
        const val = (block.props && block.props[pd.k]) ?? '';
        html += `<div class="tl-prop-label">${escHtml(t(pd.l))}${_tl_tipHtml(pd.tip ? t(pd.tip) : '')}</div>`;
        if (pd.t === 'bool') {
          html += `<label class="flex items-center gap-2 cursor-pointer mt-0.5">
            <input type="checkbox" ${val ? 'checked' : ''} onchange="_tl_setEffectProp('${pd.k}',this.checked)" class="checkbox checkbox-sm checkbox-primary">
            <span class="text-xs text-muted">${val ? t('common.enabled') : t('common.disabled')}</span>
          </label>`;
        } else if (pd.t === 'select') {
          html += `<select class="tl-prop-select" onchange="_tl_setEffectProp('${pd.k}',this.value)">`;
          (pd.opts || []).forEach(o => { const ov = o.v !== undefined ? o.v : o; const ol = o.l ? t(o.l) : ov; html += `<option value="${escHtml(ov)}" ${String(val) === String(ov) ? 'selected' : ''}>${escHtml(ol)}</option>`; });
          html += `</select>`;
        } else if (pd.t === 'sound') {
          // Sound: text input with datalist hints + play button
          html += `<div class="flex gap-1 items-center">
            <input type="text" list="tl-sound-hints" class="tl-prop-input flex-1" value="${escHtml(String(val))}" ${(pd.phKey ? 'placeholder="'+escHtml(t(pd.phKey))+'"' : pd.ph ? 'placeholder="'+escHtml(pd.ph)+'"' : '')}
              onchange="_tl_setEffectProp('${pd.k}',this.value)">
            <button class="btn-cg btn-cg-ghost" style="height:1.5rem;padding:0 0.4rem;font-size:0.65rem" onclick="_tl_playSound()" title="${t('tl.playSoundTitle')}">&#x1f50a;</button>
          </div>
          <datalist id="tl-sound-hints">${MC_SOUND_HINTS.map(s=>'<option value="'+s+'">').join('')}</datalist>`;
        } else if (pd.t === 'list') {
          // List builder: multi-row inputs with add/remove
          const items = val ? String(val).split('|').filter(Boolean) : ['', ''];
          html += `<div id="tl-list-${pd.k}">`;
          items.forEach((item, idx) => {
            html += `<div class="tl-list-item">
              <input type="text" class="tl-prop-input" value="${escHtml(item)}" ${(pd.phKey ? 'placeholder="'+escHtml(t(pd.phKey))+'"' : pd.ph ? 'placeholder="'+escHtml(pd.ph)+'"' : '')}
                onchange="_tl_updateListItem('${pd.k}',${idx},this.value)">
              ${items.length > 2 ? `<button onclick="_tl_removeListItem('${pd.k}',${idx})">&times;</button>` : ''}
            </div>`;
          });
          html += `</div>`;
          if (items.length < 8) {
            html += `<button class="tl-list-add" onclick="_tl_addListItem('${pd.k}')">${t('common.add')}</button>`;
          }
        } else if (pd.t === 'num') {
          html += `<div class="flex gap-1 items-center">
            <input type="number" class="tl-prop-input" value="${val}" ${pd.min != null ? 'min="'+pd.min+'"' : ''} ${pd.max != null ? 'max="'+pd.max+'"' : ''} ${pd.step ? 'step="'+pd.step+'"' : ''}
              onchange="_tl_setEffectProp('${pd.k}',Number(this.value))">
            ${pd.pos ? '<button class="btn-cg btn-cg-ghost" style="height:1.5rem;padding:0 0.4rem" onclick="_tl_fillPosition(\'' + pd.k + '\')" title="Ma position">📍</button>' : ''}
          </div>`;
        } else if (pd.t === 'textarea') {
          const tVal = typeof val === 'object' ? JSON.stringify(val, null, 2) : String(val || '');
          html += `<textarea class="tl-prop-input" rows="3" style="resize:vertical;font-family:'Geist Mono',monospace;font-size:0.7rem" ${(pd.phKey ? 'placeholder="'+escHtml(t(pd.phKey))+'"' : pd.ph ? 'placeholder="'+escHtml(pd.ph)+'"' : '')}
            onchange="_tl_setEffectProp('${pd.k}',this.value)">${escHtml(tVal)}</textarea>`;
        } else if (pd.t === 'species') {
          const spLow = String(val || '').toLowerCase().replace(/\s+/g, '');
          html += `<div class="flex items-center gap-1">
            <img src="https://img.pokemondb.net/sprites/home/normal/${encodeURIComponent(spLow)}.png" width="20" height="20"
              style="image-rendering:pixelated;flex-shrink:0" onerror="this.style.visibility='hidden'" onload="this.style.visibility='visible'">
            <input type="text" class="tl-prop-input" value="${escHtml(String(val))}" list="${pd.dl || 'global-species-dl'}"
              ${(pd.phKey ? 'placeholder="'+escHtml(t(pd.phKey))+'"' : pd.ph ? 'placeholder="'+escHtml(pd.ph)+'"' : '')}
              oninput="var _i=this.parentElement.querySelector('img');if(_i){_i.src='https://img.pokemondb.net/sprites/home/normal/'+encodeURIComponent(this.value.toLowerCase().replace(/\\s+/g,''))+'.png';_i.style.visibility='visible'}"
              onchange="_tl_setEffectProp('${pd.k}',this.value)">
          </div>`;
        } else {
          html += `<input type="text" class="tl-prop-input" value="${escHtml(String(val))}" ${pd.dl ? 'list="'+pd.dl+'"' : ''} ${(pd.phKey ? 'placeholder="'+escHtml(t(pd.phKey))+'"' : pd.ph ? 'placeholder="'+escHtml(pd.ph)+'"' : '')} onchange="_tl_setEffectProp('${pd.k}',this.value)">`;
        }
      });
      html += `</div>`;
    }
    // Condition
    html += `<div class="mt-3 pt-2" style="border-top:1px solid var(--bg-border)">
      <div class="text-xs font-mono text-amber mb-2">${t('tl.condition')}</div>`;
    const selCond = TL_CONDITIONS.find(c => c.t === (block.condType || 'NONE'));
    html += `<div class="tl-prop-label">${t('common.type')}${_tl_tipHtml(selCond?.tip ? t(selCond.tip) : '')}</div>`;
    html += `<select class="tl-prop-select" onchange="_tl_setProp('condType',this.value);renderProps()">`;
    TL_CONDITIONS.forEach(c => {
      html += `<option value="${c.t}" ${(block.condType || 'NONE') === c.t ? 'selected' : ''}>${escHtml(t(c.l))}</option>`;
    });
    html += `</select>`;
    if (block.condType && block.condType !== 'NONE') {
      if (['PLAYER_FLAG_TRUE','PLAYER_FLAG_FALSE'].includes(block.condType)) {
        html += `<div class="tl-prop-label">${t('tl.flagId')}</div>`;
        html += `<input type="text" class="tl-prop-input" value="${escHtml(block.condParam || '')}" onchange="_tl_setProp('condParam',this.value)">`;
      }
      if (['PLAYER_COUNT_BELOW','PLAYER_COUNT_ABOVE','SCORE_ABOVE','SCORE_BELOW','TEAM_SCORE_ABOVE','RANDOM_CHANCE'].includes(block.condType)) {
        html += `<div class="tl-prop-label">${t('tl.cond.value')}</div>`;
        html += `<input type="number" class="tl-prop-input" value="${block.condValue || 0}" onchange="_tl_setProp('condValue',Number(this.value))">`;
      }
    }
    html += `</div>`;
    // Actions
    html += `<div class="flex gap-2 mt-4 pt-3" style="border-top:1px solid var(--bg-border)">
      <button class="btn-cg btn-cg-danger flex-1" onclick="_tl_deleteBlock()">${t('common.delete')}</button>
      <button class="btn-cg btn-cg-ghost flex-1" onclick="_tl_duplicateBlock()">${t('tl.duplicate')}</button>
    </div>`;
    el.innerHTML = html;
  }

  function renderAll() {
    renderActs();
    renderRuler();
    renderTracks();
    renderProps();
  }

  // Expose for inline handlers
  window._tl_renderRuler = renderRuler;

  // ── Global handlers (exposed on window for inline onclick) ────────────
  window._tl_selectAct = (i) => {
    _tl.selectedActIndex = i;
    _tl.selectedBlockId = null;
    _tl_computeTrackLanes();
    renderAll();
  };

  window._tl_addAct = () => {
    _tl.acts.push({ id: _tlGenId(), name: t('tl.actDefault', {n: _tl.acts.length + 1}), description: '', order: _tl.acts.length, transitionType: 'DURATION', duration: 300, blocks: [] });
    _tl.selectedActIndex = _tl.acts.length - 1;
    _tl.selectedBlockId = null;
    _tlMarkDirty();
    renderAll();
  };

  window._tl_renameAct = (i) => {
    const el = document.getElementById('tl-act-name-' + i);
    if (!el) return;
    const input = document.createElement('input');
    input.type = 'text';
    input.value = _tl.acts[i].name || '';
    input.className = 'tl-prop-input';
    input.style.cssText = 'width:100%;font-size:0.7rem;padding:2px 4px;height:1.4rem';
    const commit = () => {
      const v = input.value.trim();
      if (v) { _tl.acts[i].name = v; _tlMarkDirty(); }
      renderActs();
    };
    input.addEventListener('blur', commit);
    input.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); commit(); } if (e.key === 'Escape') renderActs(); });
    el.textContent = '';
    el.appendChild(input);
    input.focus();
    input.select();
  };

  window._tl_deleteAct = async (i) => {
    if (_tl.acts.length <= 1) return;
    const ok = await confirmAction(t('events.deleteAct'));
    if (!ok) return;
    _tl.acts.splice(i, 1);
    if (_tl.selectedActIndex >= _tl.acts.length) _tl.selectedActIndex = _tl.acts.length - 1;
    _tl.selectedBlockId = null;
    _tlMarkDirty();
    renderAll();
  };

  window._tl_editActDuration = (i) => {
    const act = _tl.acts[i];
    if (!act) return;
    const curMin = Math.floor((act.duration || 0) / 60);
    const curSec = (act.duration || 0) % 60;
    const el = document.querySelector(`.tl-act-item:nth-child(${i + 1}) .text-muted`);
    if (!el) return;
    const inp = document.createElement('input');
    inp.type = 'text';
    inp.value = curMin + ':' + String(curSec).padStart(2, '0');
    inp.className = 'tl-prop-input';
    inp.style.cssText = 'width:4rem;font-size:0.65rem;padding:1px 4px;height:1.2rem;text-align:center';
    inp.placeholder = 'm:ss';
    const commit = () => {
      const parts = inp.value.split(':');
      const m = parseInt(parts[0]) || 0;
      const s = parseInt(parts[1]) || 0;
      const total = m * 60 + s;
      if (total > 0 && total !== act.duration) {
        _tlPushUndo();
        act.duration = total;
        _tlMarkDirty();
      }
      renderActs();
      renderRuler();
      renderTracks();
    };
    inp.addEventListener('blur', commit);
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); commit(); } if (e.key === 'Escape') renderActs(); });
    el.innerHTML = '';
    el.appendChild(inp);
    inp.focus();
    inp.select();
  };

  window._tl_addLane = (trackId) => {
    _tl.trackLanes[trackId] = (_tl.trackLanes[trackId] || 1) + 1;
    renderTracks();
  };

  window._tl_removeLane = (trackId) => {
    const count = _tl.trackLanes[trackId] || 1;
    if (count <= 1) return;
    const lastLane = count - 1;
    const act = _tlGetAct();
    const hasBlocks = (act.blocks || []).some(b => b.track === trackId && (b.lane || 0) === lastLane);
    if (hasBlocks) { app.toast(t('common.error'), 'warn'); return; }
    _tl.trackLanes[trackId] = lastLane;
    renderTracks();
  };

  window._tl_createBlock = (event, trackId, laneIndex) => {
    const effects = TL_EFFECTS[trackId] || [];
    if (effects.length === 0) { app.toast(t('common.error'), 'warn'); return; }
    _tlPushUndo();
    const rect = event.currentTarget.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const startSec = _tlSnap(x / _tl.zoom);
    const act = _tlGetAct();
    const block = {
      id: _tlGenId(),
      track: trackId,
      lane: laneIndex || 0,
      effectType: effects[0].t,
      start: startSec,
      duration: 30,
      repeat: 0,
      condType: 'NONE', condParam: '', condValue: 0,
      props: {},
      label: '',
      color: '',
    };
    if (!act.blocks) act.blocks = [];
    act.blocks.push(block);
    _tl.selectedBlockId = block.id;
    _tlMarkDirty();
    renderTracks();
    renderProps();
    // Animate new block
    const blkEl = document.getElementById('blk-' + block.id);
    if (blkEl) { blkEl.classList.add('tl-block-new'); blkEl.addEventListener('animationend', () => blkEl.classList.remove('tl-block-new'), { once: true }); }
  };

  window._tl_blockMouseDown = (event, blockId) => {
    if (event.button !== 0) return;
    event.stopPropagation();
    _tl.selectedBlockId = blockId;
    renderProps();
    // Highlight selected
    document.querySelectorAll('.tl-block').forEach(el => el.classList.remove('selected'));
    const el = document.getElementById('blk-' + blockId);
    if (el) el.classList.add('selected');
    // Prepare drag
    const block = _tlGetBlock(blockId);
    if (!block) return;
    _tl.dragMode = 'PENDING_MOVE';
    _tl.dragBlockId = blockId;
    _tl.dragStartMouseX = event.clientX;
    _tl.dragOrigStart = block.start;
    _tl.dragOrigDuration = block.duration;
    const onMove = (e) => {
      const dx = e.clientX - _tl.dragStartMouseX;
      if (_tl.dragMode === 'PENDING_MOVE' && Math.abs(dx) > 3) {
        _tlPushUndo();
        _tl.dragMode = 'MOVING';
        const el = document.getElementById('blk-' + blockId);
        if (el) el.classList.add('dragging');
      }
      if (_tl.dragMode === 'MOVING') {
        const newStart = _tlSnap(_tl.dragOrigStart + dx / _tl.zoom);
        block.start = newStart;
        const el = document.getElementById('blk-' + blockId);
        if (el) el.style.left = (block.start * _tl.zoom) + 'px';
      }
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      if (_tl.dragMode === 'MOVING') {
        _tlMarkDirty();
        renderTracks();
        renderProps();
      }
      _tl.dragMode = 'NONE';
      _tl.dragBlockId = null;
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  window._tl_resizeMouseDown = (event, blockId, side) => {
    event.stopPropagation();
    event.preventDefault();
    const block = _tlGetBlock(blockId);
    if (!block) return;
    _tlPushUndo();
    _tl.selectedBlockId = blockId;
    _tl.dragMode = side === 'left' ? 'RESIZE_LEFT' : 'RESIZE_RIGHT';
    _tl.dragBlockId = blockId;
    _tl.dragStartMouseX = event.clientX;
    _tl.dragOrigStart = block.start;
    _tl.dragOrigDuration = block.duration;
    const onMove = (e) => {
      const dx = e.clientX - _tl.dragStartMouseX;
      const dSec = dx / _tl.zoom;
      if (_tl.dragMode === 'RESIZE_RIGHT') {
        block.duration = Math.max(5, _tlSnap(_tl.dragOrigDuration + dSec));
        const el = document.getElementById('blk-' + blockId);
        if (el) el.style.width = Math.max(6, block.duration * _tl.zoom) + 'px';
      } else if (_tl.dragMode === 'RESIZE_LEFT') {
        const newStart = _tlSnap(_tl.dragOrigStart + dSec);
        const origEnd = _tl.dragOrigStart + _tl.dragOrigDuration;
        const newDur = origEnd - newStart;
        if (newDur >= 5 && newStart >= 0) {
          block.start = newStart;
          block.duration = newDur;
          const el = document.getElementById('blk-' + blockId);
          if (el) {
            el.style.left = (block.start * _tl.zoom) + 'px';
            el.style.width = Math.max(6, block.duration * _tl.zoom) + 'px';
          }
        }
      }
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      _tl.dragMode = 'NONE';
      _tl.dragBlockId = null;
      _tlMarkDirty();
      renderTracks();
      renderProps();
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  window._tl_setProp = (key, val) => {
    const block = _tlGetSelectedBlock();
    if (!block) return;
    block[key] = val;
    _tlMarkDirty();
    if (key === 'effectType' || key === 'start' || key === 'duration' || key === 'label' || key === 'color') renderTracks();
    if (key === 'effectType' || key === 'condType') renderProps();
  };

  window._tl_setBlockLane = (newLane) => {
    const block = _tlGetSelectedBlock();
    if (!block) return;
    const maxLane = (_tl.trackLanes[block.track] || 1) - 1;
    const clampedLane = Math.max(0, Math.min(maxLane, newLane));
    if (clampedLane === (block.lane || 0)) return;
    _tlPushUndo();
    block.lane = clampedLane;
    _tlMarkDirty();
    renderTracks();
    renderProps();
  };

  window._tl_setEffectProp = (key, val) => {
    const block = _tlGetSelectedBlock();
    if (!block) return;
    if (!block.props) block.props = {};
    block.props[key] = val;
    _tlMarkDirty();
  };

  window._tl_fillPosition = async (key) => {
    try {
      const pos = await API.admin.getPosition();
      if (!pos.online) { app.toast(t('common.offline'), 'warn'); return; }
      const block = _tlGetSelectedBlock();
      if (!block) return;
      if (!block.props) block.props = {};
      // Detect XYZ triplet base: spawnX→spawn, zoneCenterX→zoneCenter, targetX→target
      const xyzBases = ['spawn','zoneCenter','target'];
      let filled = false;
      for (const base of xyzBases) {
        if (key === base + 'X' || key === base + 'Y' || key === base + 'Z') {
          block.props[base + 'X'] = pos.x;
          block.props[base + 'Y'] = pos.y;
          block.props[base + 'Z'] = pos.z;
          filled = true;
          break;
        }
      }
      if (!filled) {
        // Single field — just fill with X
        block.props[key] = pos.x;
      }
      // Also fill dimension if the effect has a dimension prop
      if (pos.world) {
        block.props.dimension = pos.world;
      }
      _tlMarkDirty();
      renderProps();
      app.toast(t('tl.positionFilled', {x: pos.x, y: pos.y, z: pos.z, world: pos.world || '?'}), 'success');
    } catch (e) { app.toast(e.message, 'error'); }
  };

  window._tl_deleteBlock = async () => {
    if (!_tl.selectedBlockId) return;
    _tlPushUndo();
    const act = _tlGetAct();
    act.blocks = (act.blocks || []).filter(b => b.id !== _tl.selectedBlockId);
    _tl.selectedBlockId = null;
    _tlMarkDirty();
    renderTracks();
    renderProps();
  };

  window._tl_duplicateBlock = () => {
    const block = _tlGetSelectedBlock();
    if (!block) return;
    _tlPushUndo();
    const copy = JSON.parse(JSON.stringify(block));
    copy.id = _tlGenId();
    copy.start = block.start + block.duration + 5;
    const act = _tlGetAct();
    if (!act.blocks) act.blocks = [];
    act.blocks.push(copy);
    _tl.selectedBlockId = copy.id;
    _tlMarkDirty();
    renderTracks();
    renderProps();
  };

  // ── List builder helpers ──
  window._tl_updateListItem = (propKey, idx, value) => {
    const block = _tlGetSelectedBlock();
    if (!block) return;
    if (!block.props) block.props = {};
    const items = block.props[propKey] ? String(block.props[propKey]).split('|') : ['', ''];
    items[idx] = value;
    block.props[propKey] = items.join('|');
    _tlMarkDirty();
  };

  window._tl_removeListItem = (propKey, idx) => {
    const block = _tlGetSelectedBlock();
    if (!block) return;
    if (!block.props) block.props = {};
    const items = block.props[propKey] ? String(block.props[propKey]).split('|') : [];
    if (items.length <= 2) return; // min 2 options
    items.splice(idx, 1);
    block.props[propKey] = items.join('|');
    _tlMarkDirty();
    renderProps();
  };

  window._tl_addListItem = (propKey) => {
    const block = _tlGetSelectedBlock();
    if (!block) return;
    if (!block.props) block.props = {};
    const items = block.props[propKey] ? String(block.props[propKey]).split('|') : ['', ''];
    if (items.length >= 8) return; // max 8 options
    items.push('');
    block.props[propKey] = items.join('|');
    _tlMarkDirty();
    renderProps();
  };

  // ── Sound play helper ──
  window._tl_playSound = async () => {
    const block = _tlGetSelectedBlock();
    if (!block?.props?.soundId) { app.toast(t('common.error'), 'warn'); return; }
    try {
      await API.server.command(`playsound ${block.props.soundId} master @a ~ ~ ~ 1 1`);
      app.toast(t('common.success'), 'success');
    } catch (e) { app.toast(e.message, 'error'); }
  };

  window._tl_setZoom = (val) => {
    _tl.zoom = Math.max(0.5, Math.min(20, val));
    const zoomEl = document.getElementById('tl-zoom-val');
    if (zoomEl) zoomEl.textContent = _tl.zoom.toFixed(1) + 'x';
    renderRuler();
    renderTracks();
  };

  window._tl_setSnap = (val) => {
    _tl.snapInterval = val;
    document.querySelectorAll('.tl-snap-btn').forEach(b => b.classList.toggle('active', Number(b.dataset.snap) === val));
  };

  window._tl_save = _tlSave;

  window._tl_back = () => {
    cleanup();
    app.navigate('events');
  };

  // Keyboard handler
  function _tlKeyHandler(e) {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT') return;
    if (e.key === 'Escape') {
      _tl.selectedBlockId = null;
      renderTracks();
      renderProps();
    }
    if (e.key === 'Delete' || e.key === 'Backspace') {
      if (_tl.selectedBlockId) _tl_deleteBlock();
    }
    if (e.ctrlKey && e.key === 's') {
      e.preventDefault();
      _tlSave();
    }
    if (e.ctrlKey && e.key === 'z') {
      e.preventDefault();
      _tlUndo();
    }
    if (e.ctrlKey && e.key === 'c') {
      if (_tl.selectedBlockId) {
        const block = _tlGetSelectedBlock();
        if (block) { _tl._clipboard = JSON.parse(JSON.stringify(block)); app.toast(t('common.copied'), 'success'); }
      }
    }
    if (e.ctrlKey && e.key === 'v') {
      if (_tl._clipboard) {
        _tlPushUndo();
        const copy = JSON.parse(JSON.stringify(_tl._clipboard));
        copy.id = _tlGenId();
        copy.start = copy.start + copy.duration + 5;
        const act = _tlGetAct();
        if (!act.blocks) act.blocks = [];
        act.blocks.push(copy);
        _tl.selectedBlockId = copy.id;
        _tlMarkDirty();
        renderTracks();
        renderProps();
        app.toast(t('common.success'), 'success');
      }
    }
  }
  document.addEventListener('keydown', _tlKeyHandler);

  // Wheel handler for zoom/scroll
  setTimeout(() => {
    const scroller = document.getElementById('tl-tracks-scroller');
    if (scroller) {
      scroller.addEventListener('wheel', (e) => {
        if (e.ctrlKey) {
          e.preventDefault();
          const delta = e.deltaY > 0 ? -0.8 : 0.8;
          _tl_setZoom(_tl.zoom + delta);
        } else if (e.shiftKey) {
          e.preventDefault();
          scroller.scrollLeft += e.deltaY > 0 ? 40 : -40;
          renderRuler();
        }
      }, { passive: false });
      // Middle-click pan
      scroller.addEventListener('mousedown', (e) => {
        if (e.button !== 1) return;
        e.preventDefault();
        const startX = e.clientX;
        const startScroll = scroller.scrollLeft;
        const onMove = (ev) => { scroller.scrollLeft = startScroll - (ev.clientX - startX); renderRuler(); };
        const onUp = () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); scroller.style.cursor = ''; };
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
        scroller.style.cursor = 'grabbing';
      });
    }
  }, 100);

  // ── Build HTML ─────────────────────────────────────────────────────────
  setTimeout(() => renderAll(), 50);

  return `
    <div class="tl-editor" style="margin:-1.5rem">
      <!-- Header -->
      <div class="tl-header">
        <button class="btn-cg btn-cg-ghost" onclick="_tl_back()">◄ ${t('common.back')}</button>
        <div class="flex-1 flex items-center gap-3">
          <span class="text-sm font-mono text-tx">🎬 ${escHtml(evt.name)}</span>
          <span id="tl-dirty" class="text-xs text-amber font-mono" style="display:none">● ${t('common.save')}...</span>
        </div>
      </div>

      <!-- Body: 3-panel layout -->
      <div class="tl-body">
        <!-- Acts panel -->
        <div class="tl-acts-panel" id="tl-acts"></div>

        <!-- Canvas area -->
        <div class="tl-canvas-area">
          <!-- Ruler -->
          <div class="tl-ruler" id="tl-ruler"></div>
          <!-- Tracks -->
          <div class="tl-tracks-container" id="tl-tracks-scroller" onscroll="if(window._tl_renderRuler)window._tl_renderRuler()">
            <div id="tl-tracks"></div>
          </div>
        </div>

        <!-- Properties panel -->
        <div class="tl-props-panel" id="tl-props"></div>
      </div>

      <!-- Footer: zoom + snap -->
      <div class="tl-footer">
        <span class="text-xs font-mono text-muted">${t('tl.zoom')}</span>
        <button class="tl-snap-btn" onclick="_tl_setZoom(_tl.zoom - 1)">−</button>
        <span id="tl-zoom-val" class="text-xs font-mono text-tx" style="min-width:2.5rem;text-align:center">${_tl.zoom.toFixed(1)}x</span>
        <button class="tl-snap-btn" onclick="_tl_setZoom(_tl.zoom + 1)">+</button>
        <div style="width:1px;height:16px;background:var(--bg-border);margin:0 0.5rem"></div>
        <span class="text-xs font-mono text-muted">${t('tl.snap')}</span>
        <button class="tl-snap-btn ${_tl.snapInterval===5?'active':''}" data-snap="5" onclick="_tl_setSnap(5)">5s</button>
        <button class="tl-snap-btn ${_tl.snapInterval===10?'active':''}" data-snap="10" onclick="_tl_setSnap(10)">10s</button>
        <button class="tl-snap-btn ${_tl.snapInterval===30?'active':''}" data-snap="30" onclick="_tl_setSnap(30)">30s</button>
        <button class="tl-snap-btn ${_tl.snapInterval===60?'active':''}" data-snap="60" onclick="_tl_setSnap(60)">60s</button>
        <div class="flex-1"></div>
        <div class="relative" id="tl-help-wrap">
          <button class="tl-snap-btn" onclick="document.getElementById('tl-help-tooltip').classList.toggle('hidden')" title="${t('tl.shortcuts')}">?</button>
          <div id="tl-help-tooltip" class="hidden" style="position:absolute;bottom:2rem;right:0;width:260px;background:var(--bg-card);border:1px solid var(--bg-border);border-radius:6px;padding:0.75rem;z-index:50;box-shadow:0 4px 12px rgba(0,0,0,0.3)">
            <div class="text-xs font-mono text-amber mb-2">${t('tl.shortcuts')}</div>
            <div style="display:grid;grid-template-columns:auto 1fr;gap:0.25rem 0.75rem;font-size:0.65rem;font-family:'Geist Mono',monospace">
              <span style="color:var(--muted-light)">Ctrl+S</span><span style="color:var(--text-muted)">${t('common.save')}</span>
              <span style="color:var(--muted-light)">Ctrl+Z</span><span style="color:var(--text-muted)">${t('common.cancel')}</span>
              <span style="color:var(--muted-light)">Ctrl+C</span><span style="color:var(--text-muted)">${t('tl.shortcut.copy')}</span>
              <span style="color:var(--muted-light)">Ctrl+V</span><span style="color:var(--text-muted)">${t('tl.shortcut.paste')}</span>
              <span style="color:var(--muted-light)">Delete</span><span style="color:var(--text-muted)">${t('common.delete')}</span>
              <span style="color:var(--muted-light)">Escape</span><span style="color:var(--text-muted)">${t('tl.shortcut.deselect')}</span>
              <span style="color:var(--muted-light)">${t('tl.shortcut.dblClick')}</span><span style="color:var(--text-muted)">${t('tl.shortcut.createBlock')}</span>
              <span style="color:var(--muted-light)">Ctrl+Scroll</span><span style="color:var(--text-muted)">${t('tl.shortcut.zoom')}</span>
              <span style="color:var(--muted-light)">Shift+Scroll</span><span style="color:var(--text-muted)">${t('tl.shortcut.scrollH')}</span>
              <span style="color:var(--muted-light)">${t('tl.shortcut.middleClick')}</span><span style="color:var(--text-muted)">${t('tl.shortcut.panFree')}</span>
            </div>
          </div>
        </div>
      </div>
    </div>`;
}
