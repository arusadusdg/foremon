import json, os, sys

out = sys.argv[1]
os.makedirs(out + '/jobs', exist_ok=True)

jobs = {
    'lumber': dict(primary_stats=['attack'], secondary_stat='speed', base_ticks=30,
                   types=dict(grass=.20, bug=.20, fighting=.20, steel=.20, normal=.10, dark=.10),
                   abilities=dict(hypercutter=.15, sharpness=.20, technician=.10, guts=.10, hugepower=.30, purepower=.30, sheerforce=.20, toughclaws=.15),
                   moves=dict(cut=.25, furycutter=.10, xscissor=.10, slash=.10, leafblade=.10, nightslash=.10, psychocut=.10, headbutt=.05, razorleaf=.05),
                   size=dict(large=.10)),
    'mining': dict(primary_stats=['attack'], secondary_stat='defence', base_ticks=30,
                   types=dict(ground=.30, rock=.25, steel=.20, fighting=.15, flying=-.20),
                   abilities=dict(sandrush=.20, sandforce=.15, ironfist=.15, sheerforce=.20, hugepower=.30, purepower=.30, moldbreaker=.10, rockhead=.10, sturdy=.05),
                   moves=dict(dig=.20, rocksmash=.15, drillrun=.15, earthquake=.10, rockclimb=.05),
                   size=dict(large=.10)),
    'hauling': dict(primary_stats=['speed'], secondary_stat='hp', base_ticks=12,
                    types=dict(flying=.20, fighting=.15, normal=.10, dragon=.10, electric=.10),
                    abilities=dict(pickup=.15, runaway=.15, quickfeet=.20, unburden=.20, speedboost=.20, galewings=.15),
                    moves=dict(strength=.15, fly=.15, teleport=.30, extremespeed=.10, agility=.05)),
    'sorting': dict(primary_stats=['special_attack'], secondary_stat='special_defence', base_ticks=12,
                    types=dict(psychic=.30, ghost=.20, electric=.10, fairy=.10),
                    abilities=dict(frisk=.20, telepathy=.20, magician=.20, keeneye=.10, compoundeyes=.15, analytic=.15, forewarn=.10),
                    moves=dict(psychic=.15, telekinesis=.20, trick=.10, confusion=.05, teleport=.10, switcheroo=.10)),
    'farming': dict(primary_stats=['special_defence'], secondary_stat='speed', base_ticks=20,
                    types=dict(grass=.30, water=.20, bug=.20, ground=.15, fire=-.20, ice=-.15),
                    abilities=dict(harvest=.30, chlorophyll=.15, honeygather=.15, flowergift=.15, grasspelt=.10, naturalcure=.10, leafguard=.10, raindish=.10),
                    moves=dict(sweetscent=.10, growth=.15, raindance=.10, sunnyday=.10, leafage=.05, grassyterrain=.15),
                    size=dict(large=-.20, tiny=.10)),
    'smelting': dict(primary_stats=['special_attack'], secondary_stat='hp', base_ticks=160,
                     types=dict(fire=.30, rock=.10, ground=.10, water=-.30, ice=-.30, grass=-.15),
                     abilities=dict(flamebody=.25, magmaarmor=.20, flashfire=.20, drought=.20, blaze=.10, turboblaze=.25, steamengine=.20, whitesmoke=.10),
                     moves=dict(flamethrower=.10, fireblast=.15, lavaplume=.15, heatwave=.10, overheat=.15, ember=.05),
                     requires=dict(types=['fire'],
                                   abilities=['flamebody', 'magmaarmor', 'flashfire', 'drought', 'turboblaze', 'steamengine', 'whitesmoke'],
                                   moves=['flamethrower', 'ember', 'firespin', 'fireblast', 'willowisp', 'heatwave', 'lavaplume', 'overheat', 'incinerate', 'flamecharge'])),
    'building': dict(primary_stats=['attack', 'special_attack'], secondary_stat='speed', base_ticks=14,
                     types=dict(fighting=.20, rock=.15, steel=.15, psychic=.15, ground=.10, flying=.10),
                     abilities=dict(ironfist=.20, hugepower=.30, purepower=.30, technician=.15, sturdy=.10, skilllink=.15),
                     moves=dict(strength=.15, stealthrock=.10, psychic=.10, telekinesis=.10, rockslide=.05),
                     size=dict(large=.10)),
    'demolition': dict(primary_stats=['attack'], secondary_stat='speed', base_ticks=20,
                       types=dict(fighting=.25, rock=.15, steel=.15, ground=.15, dark=.10, dragon=.10),
                       abilities=dict(sheerforce=.20, moldbreaker=.20, hugepower=.30, purepower=.30, ironfist=.15, reckless=.15),
                       moves=dict(brickbreak=.25, rocksmash=.20, explosion=.15, selfdestruct=.15, earthquake=.15, superpower=.10),
                       size=dict(large=.15)),
    'guard': dict(primary_stats=['attack', 'special_attack'], secondary_stat='speed', base_ticks=20,
                  types=dict(fighting=.30, dark=.15, dragon=.15, steel=.15, fire=.10, electric=.10, fairy=.10),
                  abilities=dict(intimidate=.20, justified=.20, guts=.15, hugepower=.30, purepower=.30, toughclaws=.15, sheerforce=.15, keeneye=.10, innerfocus=.10),
                  moves=dict(closecombat=.15, brickbreak=.10, nightslash=.10, flamethrower=.10, thunderbolt=.10, protect=.10, bulkup=.10, quickattack=.05),
                  size=dict(large=.10)),
}
for k, v in jobs.items():
    json.dump(v, open(f'{out}/jobs/{k}.json', 'w'), indent=2)

natures = {}
for n in ['lonely', 'brave', 'adamant', 'naughty']: natures[n] = 'strong'
for n in ['bold', 'relaxed', 'impish', 'lax']: natures[n] = 'sturdy'
for n in ['timid', 'hasty', 'jolly', 'naive']: natures[n] = 'eager'
for n in ['modest', 'mild', 'quiet', 'rash']: natures[n] = 'clever'
for n in ['calm', 'gentle', 'sassy', 'careful']: natures[n] = 'careful'

rules = dict(
    stat_base=.5, stat_primary_weight=.7, stat_secondary_weight=.3, stat_divisor=200.0, stat_min=.5, stat_max=2.0,
    type_min=.7, type_max=1.6, second_type_share=.5, move_cap=.35, final_min=.25, final_max=3.0,
    friendship_min=.90, friendship_span=.20, fed_bonus=.15,
    large_width=1.5, large_height=3.0, tiny_width=.5, tiny_height=.6,
    carry_base=8.0, carry_per_hp=0.3334, carry_min=4, carry_max=256, carry_large=2.0, carry_tiny=.5, reach_large=2,
    mining_tier_levels=[1, 10, 25, 45], mining_tier_types=['rock', 'ground', 'steel'],
    star_thresholds=[.75, 1.0, 1.35, 1.8],
    unlocks=dict(
        lumber=[dict(level=10, id="leaf_sweep", value=1), dict(level=20, id="timber", value=4), dict(level=35, id="timber", value=12), dict(level=50, id="timber", value=256)],
        mining=[dict(level=15, id="vein", value=4), dict(level=30, id="vein", value=10), dict(level=45, id="vein", value=24)],
        farming=[dict(level=15, id="sweep", value=1), dict(level=35, id="sweep", value=2)],
        smelting=[dict(level=20, id="batch", value=2), dict(level=40, id="batch", value=4)],
        hauling=[dict(level=20, id="load", value=1.5), dict(level=40, id="load", value=2)],
        sorting=[dict(level=20, id="load", value=1.5), dict(level=40, id="load", value=2)],
        building=[dict(level=25, id="multi", value=2), dict(level=45, id="multi", value=3)],
        demolition=[dict(level=25, id="multi", value=2), dict(level=45, id="multi", value=4)],
        guard=[dict(level=25, id="cleave", value=2), dict(level=45, id="cleave", value=3)],
    ),
    capability_abilities=dict(levitate='flight'),
    capability_moves=dict(fly='flight', rockclimb='flight', dive='underwater'),
    capability_types=dict(water='underwater', fire='lava'),
    natures=natures,
    styles=dict(
        balanced=dict(),
        eager=dict(speed=1.10, stamina_drain=1.25),
        strong=dict(carry=1.25),
        sturdy=dict(stamina=1.25),
        clever=dict(xp=1.15),
        careful=dict(bonus_yield=.05)),
    perks=dict(pickup='vacuum', harvest='double_harvest', superluck='fortune', serenegrace='fortune', compoundeyes='fortune'),
    move_perks=dict(cut='tree_feller', dig='vein_miner'),
    quirks=dict(truant='truant', slowstart='slow_start'),
)
json.dump(rules, open(f'{out}/rules.json', 'w'), indent=2)


def node(id, branch, max, cost, x, y, base=0.0, per=1.0, growth=1.6, req=None, icon='minecraft:paper'):
    d = dict(id=id, branch=branch, max=max, base_cost=cost, cost_growth=growth, base=base, per_level=per, icon=icon, x=x, y=y)
    if req:
        d['requires'] = req
    return d


N = [
    # Foreman: crew and territory
    node('work_area', 'foreman', 5, 40, 0, 0, base=8, per=8, icon='minecraft:spyglass'),
    node('vertical_range', 'foreman', 6, 30, 1, 0, base=8, per=8, icon='minecraft:ladder'),
    node('crew_size', 'foreman', 5, 60, 0, 1, base=1, per=1, growth=1.9, icon='minecraft:player_head'),
    node('job_sites', 'foreman', 5, 80, 1, 1, base=1, per=1, growth=1.9, icon='minecraft:chest'),
    node('linked_chests', 'foreman', 5, 30, 0, 2, base=2, per=2, icon='minecraft:chest_minecart'),
    node('filter_slots', 'foreman', 5, 25, 1, 2, base=3, per=3, icon='minecraft:item_frame'),
    node('remote_control', 'foreman', 1, 200, 0, 3, req={'job_sites': 1}, icon='minecraft:recovery_compass'),
    node('night_shift', 'foreman', 1, 400, 1, 3, req={'crew_size': 2}, icon='minecraft:clock'),
    # Labour: how well the work gets done
    node('work_ethic', 'labour', 5, 50, 0, 0, per=.05, icon='minecraft:sugar'),
    node('speed_lumber', 'labour', 5, 40, 1, 0, per=.10, icon='minecraft:iron_axe'),
    node('speed_mining', 'labour', 5, 40, 2, 0, per=.10, icon='minecraft:iron_pickaxe'),
    node('speed_logistics', 'labour', 5, 40, 3, 0, per=.10, icon='minecraft:chest_minecart'),
    node('speed_farming', 'labour', 5, 40, 4, 0, per=.10, icon='minecraft:iron_hoe'),
    node('speed_smelting', 'labour', 5, 40, 5, 0, per=.10, icon='minecraft:blast_furnace'),
    node('speed_building', 'labour', 5, 40, 6, 0, per=.10, icon='minecraft:bricks'),
    node('speed_demolition', 'labour', 5, 40, 7, 0, per=.10, icon='minecraft:tnt'),
    node('speed_guard', 'labour', 5, 40, 8, 0, per=.10, icon='minecraft:iron_sword'),
    node('sharp_claws', 'labour', 3, 90, 8, 1, per=.25, req={'speed_guard': 1}, icon='minecraft:diamond_sword'),
    node('lookout', 'labour', 3, 80, 8, 2, base=0, per=8, req={'speed_guard': 1}, icon='minecraft:spyglass'),
    node('green_thumb', 'labour', 3, 90, 4, 2, per=.10, req={'speed_farming': 2}, icon='minecraft:bone_meal'),
    node('quick_fetch', 'labour', 3, 70, 6, 2, per=.50, req={'speed_building': 1}, icon='minecraft:barrel'),
    node('tree_feller', 'labour', 1, 250, 1, 1, req={'speed_lumber': 2}, icon='minecraft:oak_log'),
    node('bone_meal', 'labour', 3, 120, 1, 2, req={'speed_lumber': 1}, icon='minecraft:bone_meal'),
    node('deep_digging', 'labour', 2, 200, 2, 1, req={'speed_mining': 1}, growth=2.5, icon='minecraft:deepslate_diamond_ore'),
    node('vein_miner', 'labour', 1, 250, 2, 2, req={'speed_mining': 2}, icon='minecraft:raw_iron'),
    node('prospector', 'labour', 4, 80, 2, 3, per=.05, req={'speed_mining': 1}, icon='minecraft:emerald'),
    node('bountiful_harvest', 'labour', 4, 80, 4, 1, per=.08, req={'speed_farming': 1}, icon='minecraft:wheat'),
    node('twin_furnace', 'labour', 1, 250, 5, 1, req={'speed_smelting': 2}, icon='minecraft:furnace'),
    node('master_builder', 'labour', 1, 300, 6, 1, req={'speed_building': 3}, icon='minecraft:scaffolding'),
    node('clean_salvage', 'labour', 3, 80, 7, 1, base=.70, per=.10, req={'speed_demolition': 1}, icon='minecraft:brush'),
    # Care: keeping the crew happy
    node('endurance', 'care', 5, 40, 0, 0, per=20, icon='minecraft:golden_carrot'),
    node('power_nap', 'care', 5, 40, 1, 0, per=.12, icon='minecraft:red_bed'),
    node('hearty_meals', 'care', 4, 50, 2, 0, per=.25, icon='minecraft:sweet_berries'),
    node('fast_learner', 'care', 5, 60, 3, 0, per=.20, icon='minecraft:book'),
    node('second_wind', 'care', 4, 80, 0, 1, per=.05, req={'endurance': 2}, icon='minecraft:feather'),
    node('bonding', 'care', 3, 80, 1, 1, per=.50, icon='minecraft:poppy'),
    node('training', 'care', 3, 100, 2, 1, per=.50, icon='minecraft:experience_bottle'),
    node('safety_gear', 'care', 1, 200, 3, 1, icon='minecraft:turtle_helmet'),
    node('campfire_rest', 'care', 1, 150, 4, 0, icon='minecraft:campfire'),
    # Logistics: moving things around
    node('pack_mule', 'logistics', 4, 50, 0, 0, per=.25, icon='minecraft:bundle'),
    node('fleet_feet', 'logistics', 4, 50, 1, 0, per=.08, icon='minecraft:rabbit_foot'),
    node('long_haul', 'logistics', 4, 50, 2, 0, base=24, per=16, icon='minecraft:rail'),
    node('supply_lines', 'logistics', 5, 80, 2, 1, base=500, per=900, icon='minecraft:filled_map'),
    node('dispatch', 'logistics', 5, 60, 3, 1, base=0, per=1, icon='minecraft:compass'),
    node('express_lanes', 'logistics', 3, 80, 2, 2, per=.15, req={'supply_lines': 1}, icon='minecraft:powered_rail'),
    node('item_magnet', 'logistics', 4, 40, 3, 0, base=2, per=1.5, icon='minecraft:lodestone'),
    node('tidy_up', 'logistics', 1, 150, 0, 1, icon='minecraft:barrel'),
    node('quick_recall', 'logistics', 3, 60, 1, 1, per=.20, icon='minecraft:ender_pearl'),
]
skills = dict(
    modes=dict(
        relaxed=dict(cost_multiplier=.5, xp_multiplier=1.5,
                     start=dict(work_area=1, crew_size=1, job_sites=1, linked_chests=1, filter_slots=1, vertical_range=1)),
        normal=dict(cost_multiplier=1.0, xp_multiplier=1.0, start={}),
        hardcore=dict(cost_multiplier=2.0, xp_multiplier=.75, start={})),
    nodes=N)
json.dump(skills, open(f'{out}/skills.json', 'w'), indent=2)
print(len(N), 'skill nodes;', len(jobs), 'jobs')
