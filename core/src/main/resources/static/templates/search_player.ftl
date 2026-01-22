<!DOCTYPE html>
<html lang="en">

<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Document</title>
    <link rel="stylesheet" href="${httpServer}/static/css/search_player.css?v=${.now?long}">
</head>
<body>
<div id="content-container">
    <div id="header">
        <div id="banner_user_info" style="--season-bg: url('${seasonBannerUrl?html}');">
            <div class="profile-image-wrapper">
                <#if profileImageUrl??>
                    <img src="${httpServer}${profileImageUrl}" alt=""/>
                <#else>
                    <img src="" alt=""/>
                </#if>
            </div>
            <div id="top">
                <div class="level">Lv.${level}</div>
                <div class="nickname">${nickName}</div>
                <p>${seasonPlayTimeText}</p>
            </div>
        </div>
        <div id="describe">
            <div id="logo">Design inspired by DakGG •
                Powered by LuoRenMu
            </div>
        </div>
    </div>
    <div id="body">
        <div id="left">
            <div id="rank">
                <h4>${mode}(${season})</h4>
                <div id="score">
                    <div id="rp_img">
                        <img src="${httpServer}${data.tierImageUrl}" alt="">
                    </div>
                    <div id="rp_box">
                        <div id="rp">
                            ${data.rp}
                        </div>
                        <div id="rp_name">
                            ${data.rpName}
                        </div>
                        <#if (data.globalRankText)?has_content || (data.localRankText)?has_content>
                            <div id="rank_place">
                                <#if (data.globalRankText)?has_content>
                                    <div class="rank_place_item">${data.globalRankText}</div>
                                </#if>
                                <#if (data.localRankText)?has_content>
                                    <div class="rank_place_item">local ${data.localRankText}</div>
                                </#if>
                            </div>
                        </#if>
                    </div>
                </div>
                <div id="record">
                    <div>
                        <div class="record_box">
                            <h4>平均TK</h4>
                            <h4>${data.avgTk}</h4>
                        </div>
                        <div class="record_box">
                            <h4>TOP 1</h4>
                            <h4>${data.top1}</h4>
                        </div>
                        <div class="record_box">
                            <h4>游戏场次</h4>
                            <h4>${data.play}</h4>
                        </div>

                        <div class="record_box">
                            <h4>平均击杀</h4>
                            <h4>${data.avgKill}</h4>
                        </div>
                        <div class="record_box">
                            <h4>TOP 2</h4>
                            <h4>${data.top2}</h4>
                        </div>
                        <div class="record_box">
                            <h4>平均伤害</h4>
                            <h4>${data.avgDmg}</h4>
                        </div>
                        <div class="record_box">
                            <h4>平均助攻</h4>
                            <h4>${data.avgAssists}</h4>
                        </div>
                        <div class="record_box">
                            <h4>TOP 3</h4>
                            <h4>${data.top3}</h4>
                        </div>
                        <div class="record_box">
                            <h4>平均排名</h4>
                            <h4>${data.avgRank}</h4>
                        </div>
                    </div>
                </div>

                <#if mmrStats?? && (mmrStats.chart)??>
                    <#assign chart = mmrStats.chart>
                    <#if ((chart.circles)![])?has_content>
                        <#assign _chartW = (chart.width)!320>
                        <#assign _chartH = (chart.height)!130>
                        <#assign _padL = (chart.paddingLeft)!56>
                        <#assign _padR = (chart.paddingRight)!12>
                        <#assign _padT = (chart.paddingTop)!10>
                        <#assign _padB = (chart.paddingBottom)!22>
                        <#assign _plotW = (_chartW - _padL - _padR)>
                        <#assign _plotH = (_chartH - _padT - _padB)>
                        <#assign _mmrValues = (mmrStats.mmr)![]>
                        <#assign _mmrLabels = (mmrStats.mmrDate)![]>
                        <#assign _n = _mmrValues?size>
                        <#if _mmrLabels?size < _n><#assign _n = _mmrLabels?size></#if>
                        <div id="rank_stats">
                            <svg id="rank_svg"
                                 width="${_chartW}"
                                 height="${_chartH}"
                                 viewBox="0 0 ${_chartW} ${_chartH}"
                                 overflow="visible"
                                 style="font-family: Microsoft YaHei, sans-serif; font-size: 12px; fill: #666;"
                                 xmlns="http://www.w3.org/2000/svg">
                                <g class="mmr-grid">
                                    <#if ((chart.yTicks)![])?has_content>
                                        <#list (chart.yTicks)![] as tick>
                                            <line x1="${_padL}" y1="${tick.y}"
                                                  x2="${_chartW - _padR}" y2="${tick.y}"
                                                  stroke="rgba(0,0,0,0.08)" stroke-width="1" shape-rendering="crispEdges"/>
                                            <text x="${_padL - 10}" y="${tick.y}"
                                                  text-anchor="end"
                                                  dominant-baseline="middle"
                                                  fill="#666">${tick.label}</text>
                                        </#list>
                                    <#elseif _n gt 0>
                                        <#assign _sorted = (_mmrValues[0.._n-1])?sort>
                                        <#assign _rawMin = _sorted[0]>
                                        <#assign _rawMax = _sorted[_sorted?size - 1]>
                                        <#assign _span = _rawMax - _rawMin>
                                        <#if _span lt 1><#assign _span = 1></#if>
                                        <#assign _step =
                                            (_span lte 30)?then(10,
                                            (_span lte 100)?then(20,
                                            (_span lte 250)?then(50,
                                            (_span lte 500)?then(100,
                                            (_span lte 1000)?then(200, 500)))))>
                                        <#assign _minY = ((_rawMin / _step)?floor * _step)?int>
                                        <#assign _maxY = ((_rawMax / _step)?ceiling * _step)?int>
                                        <#if _maxY == _minY><#assign _maxY = _minY + _step></#if>
                                        <#assign _rangeY = _maxY - _minY>
                                        <#assign _tickCount = ((_rangeY / _step)?int + 1)>
                                        <#list 0.._tickCount-1 as _i>
                                            <#assign _v = (_minY + _i * _step)>
                                            <#assign _t = (_v - _minY) / _rangeY>
                                            <#assign _y = (_padT + (1 - _t) * _plotH)?round>
                                            <line x1="${_padL}" y1="${_y}"
                                                  x2="${_chartW - _padR}" y2="${_y}"
                                                  stroke="rgba(0,0,0,0.08)" stroke-width="1" shape-rendering="crispEdges"/>
                                            <text x="${_padL - 10}" y="${_y}"
                                                  text-anchor="end"
                                                  dominant-baseline="middle"
                                                  fill="#666">${_v}</text>
                                        </#list>
                                    </#if>
                                </g>

                                <g class="mmr-xlabels">
                                    <#if ((chart.xLabels)![])?has_content>
                                        <#list (chart.xLabels)![] as xl>
                                            <text x="${xl.x}" y="${_chartH - _padB + 6}"
                                                  text-anchor="middle"
                                                  dominant-baseline="hanging"
                                                  fill="#666">${xl.label}</text>
                                        </#list>
                                    <#elseif _n gt 0>
                                        <#assign _maxXLabels = 7>
                                        <#assign _stepX = ((_n + _maxXLabels - 1) / _maxXLabels)?int>
                                        <#if _stepX lt 1><#assign _stepX = 1></#if>
                                        <#list 0.._n-1 as _i>
                                            <#if (_i % _stepX) == 0>
                                                <#assign _x = (_n lte 1)?then((_padL + _plotW / 2)?round, (_padL + (_plotW * _i) / (_n - 1))?round)>
                                                <text x="${_x}" y="${_chartH - _padB + 6}"
                                                      text-anchor="middle"
                                                      dominant-baseline="hanging"
                                                      fill="#666">${_mmrLabels[_i]}</text>
                                            </#if>
                                        </#list>
                                        <#if _n gt 1 && ((_n - 1) % _stepX) != 0>
                                            <#assign _i = _n - 1>
                                            <#assign _x = (_padL + (_plotW * _i) / (_n - 1))?round>
                                            <text x="${_x}" y="${_chartH - _padB + 6}"
                                                  text-anchor="middle"
                                                  dominant-baseline="hanging"
                                                  fill="#666">${_mmrLabels[_i]}</text>
                                        </#if>
                                    </#if>
                                </g>

                                <polyline class="mmr-line" fill="none" points="${(chart.points)!''}"
                                          stroke="rgb(202, 164, 40)" stroke-width="2"/>
                                <#list (chart.circles)![] as p>
                                    <circle class="mmr-point" cx="${p.x}" cy="${p.y}" r="4"
                                            fill="rgb(202, 164, 40)" stroke="#ffffff" stroke-width="1"/>
                                </#list>
                            </svg>
                        </div>
                    </#if>
                </#if>


            </div>

            <#if characterUseStats?has_content>
                <section>
                    <table id="rank_character_stats">
                        <thead>
                        <tr>
                            <th class="character">角色</th>
                            <th class="win-rate">胜率</th>
                            <th class="get-rp">RP</th>
                            <th class="avg-rank">平均排名</th>
                            <th class="avg-dmg">平均伤害</th>
                        </tr>
                        </thead>
                        <tbody>
	                        <#list characterUseStats as character>
	                            <tr>
	                                <td class="character">
	                                    <div class="character_cell">
	                                        <div class="image-wrapper"><img
	                                                    src="${httpServer}${character.imgUrl}"
	                                                    alt=""></div>
	                                        <div class="info">
	                                            <div class="name">${character.characterName}</div>
	                                            <div class="plays">${character.characterPlay} 游戏</div>
	                                        </div>
	                                    </div>
	                                </td>
                                <td class="win-rate">${character.winRate}</td>
                                <td class="get-rp">
                                    <#if character.getRP gte 0>
                                        <svg xmlns="http://www.w3.org/2000/svg" width="8" height="5" viewBox="0 0 8 5"
                                             fill="none"
                                             style="transform: none;">
                                            <path d="M6.75 4.75C7.17188 4.75 7.38281 4.25781 7.07812 3.95312L4.07812 0.953125C3.89062 0.765625 3.58594 0.765625 3.39844 0.953125L0.398438 3.95312C0.09375 4.25781 0.304688 4.75 0.726562 4.75H6.75Z"
                                                  fill="#FF4655"></path>
                                        </svg>
                                        ${character.getRP}
                                    <#else>
                                        <svg xmlns="http://www.w3.org/2000/svg" width="8" height="5" viewBox="0 0 8 5"
                                             fill="none" style="transform: rotate(180deg);">
                                            <path
                                                    d="M6.75 4.75C7.17188 4.75 7.38281 4.25781 7.07812 3.95312L4.07812 0.953125C3.89062 0.765625 3.58594 0.765625 3.39844 0.953125L0.398438 3.95312C0.09375 4.25781 0.304688 4.75 0.726562 4.75H6.75Z"
                                                    fill="#5393ca"></path>
                                        </svg>
                                        ${character.getRP}
                                    </#if>
                                </td>
                                <td class="avg-rank">${character.avgRank}</td>
                                <td class="avg-dmg">${character.avgDmg}</td>
                            </tr>
                        </#list>
                        </tbody>
                    </table>
                </section>
            </#if>
            <#if recentPlayers?has_content>
                <section>
                    <table id="recent_play">
                        <thead>
                        <tr>
                            <th class="character">一起游戏的玩家 (最近组排队友)</th>
                            <th class="win-rate">胜率</th>
                            <th class="avg-rank">平均排名</th>
                        </tr>
                        </thead>
                        <tbody>
	                        <#list recentPlayers as recentPlayer>
	                            <tr>
	                                <td class="character">
	                                    <div class="character_cell">
	                                        <div class="image-wrapper"><img
	                                                    src="${httpServer}${recentPlayer.imageWrapperUrl}"
	                                                    alt=""></div>
	                                        <div class="info">
	                                            <div class="name">${recentPlayer.nickname}</div>
	                                            <div class="plays">${recentPlayer.plays} 游戏</div>
	                                        </div>
	                                    </div>
	                                </td>
                                <td class="win-rate">
                                    ${recentPlayer.winRate}
                                </td>
                                <td class="avg-rank">
                                    ${recentPlayer.avgRank}
                                </td>
                            </tr>
                        </#list>

                        </tbody>
                    </table>
                </section>
            </#if>
        </div>
        <div id="right">
            <#if rating??>
                <div id="lomu_rating">
                    ${rating}
                </div>
            </#if>
	            <#list matches as match>
	                <div class="war_record">
	                    <#if match.rank == 99>
	                        <div class="war_record_left_escape"></div>
	                        <div class="war_record_right_escape"></div>
                    <#elseif match.rank gte 3 || (match.type == "钴协议" && match.rank == 2) >
                        <div class="war_record_left_top3"></div>
                        <div class="war_record_right_top3"></div>
                    <#else>
                        <div class="war_record_left_top${match.rank}"></div>
                        <div class="war_record_right_top${match.rank}"></div>
                    </#if>
                    <div class="war_record1">
                        <div class="war_rank">
                            <#if match.rank == 99>
                                <div style="color:#475482">逃离</div>
                            <#elseif match.type == "钴协议" && match.rank == 1>
                                <div>胜利</div>
                            <#elseif match.type == "钴协议" && match.rank == 2>
                                <div>失败</div>
                            <#elseif match.rank == 1>
                                <div style="color: #11B288">#${match.rank}</div>
                            <#elseif match.rank == 2>
                                <div style="color: #207AC7">#${match.rank}</div>
                            <#else>
                                <div>#${match.rank}</div>
                            </#if>
                            <div>${match.type}</div>
                            <div>${match.dateHour}</div>
                            <div>${match.dateMonth}</div>
                        </div>
                        <div class="war_record_character_info">
                            <div class="hero_avatar">
                                <img src="${httpServer}${match.characterAvatarUrl}"
                                     alt="">
                            </div>
                            <div class="character_name">${match.characterName}</div>
                        </div>
                        <div class="skill">
                            <div class="weapon">
                                <img src="${httpServer}${match.weaponUrl}"
                                     alt="">
                            </div>
                            <div class="trait">
                                <img src="${httpServer}${match.traitSkillUrl}"
                                     alt="">
                            </div>
                            <div class="trait">
                                <img src="${httpServer}${match.tacticalSkillUrl}"
                                     alt="">
                            </div>

                            <div class="trait">
                                <img src="${httpServer}${match.traitSkillGroupUrl}"
                                         alt="">
                            </div>
                        </div>

                        <div class="play_stat">
                            <div class="stat">
                                ${match.tk} <span>/</span> ${match.kill} <span>/</span> ${match.assist}
                            </div>
                            <div class="label">
                                TK <span>/</span> K <span>/</span> A
                            </div>
                        </div>
                        <div class="play_data">
                            <div class="damage">
                                <div class="play_data_title">${match.dmg}</div>
                                <div class="play_data_label">DMG</div>
                            </div>
                            <#if match.type == "排位">
                                <div class="rp">
                                    <div class="play_data_title">${match.rp}
                                        <#if match.rpChange gte 0>
                                            <svg xmlns="http://www.w3.org/2000/svg" width="8" height="5"
                                                 viewBox="0 0 8 5" fill="none"
                                                 style="transform: none;">
                                                <path d="M6.75 4.75C7.17188 4.75 7.38281 4.25781 7.07812 3.95312L4.07812 0.953125C3.89062 0.765625 3.58594 0.765625 3.39844 0.953125L0.398438 3.95312C0.09375 4.25781 0.304688 4.75 0.726562 4.75H6.75Z"
                                                      fill="#FF4655"></path>
                                            </svg>
                                            <span style="color: #FF4655">${match.rpChange}</span>
                                        <#else>
                                            <svg xmlns="http://www.w3.org/2000/svg" width="8" height="5"
                                                 viewBox="0 0 8 5"
                                                 fill="none" style="transform: rotate(180deg);">
                                                <path
                                                        d="M6.75 4.75C7.17188 4.75 7.38281 4.25781 7.07812 3.95312L4.07812 0.953125C3.89062 0.765625 3.58594 0.765625 3.39844 0.953125L0.398438 3.95312C0.09375 4.25781 0.304688 4.75 0.726562 4.75H6.75Z"
                                                        fill="#5393ca"></path>
                                            </svg>
                                            <span style="color: #5393CA">${match.rpChange}</span>
                                        </#if>
                                    </div>
                                    <div class="play_data_label">RP</div>
                                </div>
                            <#else>
                                <div class="rp">
                                    <div class="play_data_title">${match.kda}</div>
                                    <div class="play_data_label">KDA</div>
                                </div>
                            </#if>
                            <div class="route">
                                <div class="play_data_title">${match.routeId}</div>
                                <div class="play_data_label">路径ID
                                </div>
                            </div>
                        </div>

	                        <ul class="item_box">
	                            <#list match.equips as equip>
	                                <li class="item">
                                    <#if equip.itemBgUrl != "" >
                                        <img class="item_bg" src="${httpServer}${equip.itemBgUrl}"
                                             alt="">
                                    </#if>
                                    <#if equip.itemUrl != "" >
                                        <img class="item_img"
                                             src="${httpServer}${equip.itemUrl}" alt="">
                                    </#if>
	                                </li>
	                            </#list>
	                        </ul>
	                        <div class="game_id">Game ID
	                            ${match.serverName}-${match.gameId}
	                            (${match.version})
	                        </div>
	                    </div>
                    <#if match.teamMates??>
                        <#list match.teamMates as teamMate>
                            <div class="war_record2">
                                <div class="teammate_name">
                                    <div class="play_name">${teamMate.nickName}</div>
                                    <div class="teammate_rp">
                                        <#if teamMate.rpImageUrl?has_content>
                                            <div class="teammate_rp_img"><img
                                                        src="${httpServer}${teamMate.rpImageUrl}" alt=""></div>
                                        </#if>
                                        <span>${teamMate.rp} RP</span>
                                    </div>
                                </div>
                                <div class="hero_avatar">
                                    <img src="${httpServer}${teamMate.avatarUrl}"
                                         alt="">
                                </div>
                                <div class="skill">
                                    <div class="weapon">
                                        <img src="${httpServer}${teamMate.weaponUrl}"
                                             alt="">
                                    </div>
                                    <div class="trait">
                                        <img src="${httpServer}${teamMate.traitSkillUrl}"
                                             alt="">
                                    </div>
                                    <div class="trait">
                                        <img src="${httpServer}${teamMate.skillUrl}"
                                             alt="">
                                    </div>

                                    <div class="trait">
                                        <img src="${httpServer}${teamMate.traitSkillGroupUrl}"
                                                 alt="">
                                    </div>
                                </div>
                                <div class="play_stat">
                                    <div class="stat">
                                        ${teamMate.tk} <span>/</span> ${teamMate.kill} <span>/</span> ${teamMate.assist}
                                    </div>
                                    <div class="label">
                                        TK <span>/</span> K <span>/</span> A
                                    </div>
                                </div>
                                <div class="play_data">
                                    <div class="damage">
                                        <div class="play_data_title">${teamMate.dmg}</div>
                                        <div class="play_data_label">DMG</div>
                                    </div>
                                </div>

                                <ul class="item_box">
                                    <#list teamMate.equips as teamMateEquip>
                                        <li class="item">
                                            <img class="item_bg" src="${httpServer}${teamMateEquip.itemBgUrl}"
                                                 alt="">
                                            <img class="item_img"
                                                 src="${httpServer}${teamMateEquip.itemUrl}" alt="">
                                        </li>
                                    </#list>
                                </ul>
                            </div>
                        </#list>
                    </#if>
                </div>
            </#list>
        </div>
    </div>
</div>
</body>
</html>
