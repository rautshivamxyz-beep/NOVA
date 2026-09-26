#!/usr/bin/env python3
"""NOVA v7.6.0 app patch: the big reliability + quality pack.

1. Greeting ("hi") resets a dirty/stale engine context first - iÐÕÍ•Ñ¼(€€‰”…¹ÍÝ•É•Ý¥Ñ ½±ÍÑÉ¥Ðµµ½‘”‰½¥±•ÉÁ±…Ñ”•¡½¥¹œ€ ‰É½´•¹•É…°(€€­¹½Ý±•‘”¸¸¸ˆ¤¥¹ÍÑ•…½˜„É••Ñ¥¹œ¸(È¸I•Á±ä±•…¹ÕÀèÉ•Á•…Ñ•€‰É½´•¹•É…°­¹½Ý±•‘”¸¸¸ˆµ…É­•ÉÌ€¡­••À½¹±ä(€€Ñ¡”™¥ÉÍÐ¤…¹€‰Q¡”™¥¹…°…¹ÍÝ•È¥Ìèˆ…¹¹½Õ¹•µ•¹ÑÌ…É”ÍÑÉ¥ÁÁ•¸(Ì¸9½Ñ•ÌÉ•±•Ù…¹”…Ñ”è½¹”Í¡…É•Ý½É¹¼±½¹•È¥¹©•ÑÌ©Õ¹¬¹½Ñ•ÌÝ¥Ñ (€€„½¹™¥‘•¹Ðµ±½½­¥¹œ¥Ñ…Ñ¥½¸€¡Ñ¡”€‰MÕ‰¡…Ì¡…¹‘É„	½Í”ˆ™…¥±ÕÉ”¤¸(Ð¸1…Q•`¥¹ÍÑÉÕÑ¥½¸½¹±ä™½ÈÉ•…°ÕÍ•ÈÅÕ•ÍÑ¥½¹Ì€´¥¹Ñ•É¹…°ÁÉ½µÁÑÌ(€€€¡¡¥ÁÌ½½¹Ñ¥¹Õ”½É•ÑÉä¤…É”¹¼±½¹•È€‰µ…Ñ¡äˆ¸(Ô¸A½ÍÐµÉ•Á±ä¡…ÐµÍÝ¥Ñ Õ…Éè…ÕÑ¼µ½¹Ñ¥¹Õ”½½µÁ…Ñ¥½¸É”µ¡•¬Ñ¡”(€€¡…Ð…™Ñ•ÈÑ¡”Í…Ù”ÍÕÍÁ•¹Í¥½¸€¡Ý…ÌèÉ…Í ½ÈÉ½ÍÌµ¡…Ð½ÉÉÕÁÑ¥½¸¤¸(Ø¸½Á•¹¡…Ð¹¼±½¹•È±•…­ÌÑ¡”…ÑÑ…¡•‘½Õµ•¹Ð¥¹Ñ¼Ñ¡”½Á•¹•¡…Ð¸(Ü¸½µÁ…Ñ¥½¸½¹±äÝÉ¥Ñ•Ì¥¹Ñ¼Ñ¡”¡…Ð¥ÐÝ…ÌÍÑ…ÉÑ•™½È¸(à¸Ñ½¬½Ì½Õ¹ÑÌ½¹±äÑ¡”ÕÉÉ•¹ÐÍ•µ•¹Ð€¡½¹Ñ¥¹Õ…Ñ¥½¹ÌÉ•…øÉà¤¸(ä¸ÑåÁ•Ñ•áÐ¥Ì­•ÁÐÝ¡•¸Í•¹ ¤‘½•Ì¹½ÐÁÉ½••€¡½µÁ…Ñ¥½¸½¹¼µ½‘•°¤¸(ÄÀ¸€‰Ñ½¹¥¡Ð…Ð€äèÌÀˆ¥Ì¹½ÜA4ì10µALÁ¡½¹”½µµ…¹‘ÌÝ½É¬ìQQL(€€€É•…µ…±½ÕÍÕÉÙ¥Ù•Ì…¸ÕÑÑ•É…¹”•ÉÉ½Èì=HÉ•½¹¥é•È±½Í•½¸(€€€™…¥±ÕÉ”ìÉÕ¹)Ì]•‰Y¥•Ü‘•ÍÑÉ½å•Ý¥Ñ ¥ÑÌ‘¥…±½œìÍ¡…É•Ñ•áÐ¹¼(€€€±½¹•ÈÍ¥±•¹Ñ±ä‘É½ÁÁ•Ý¡¥±”•¹•É…Ñ¥¹œì‰½Õ¹‘•Í¡…É•µ‘½ŒÉ•…ì(€€€¹½Ñ•Ì…¡”Ý…Éµ•¥¸Ñ¡”‰…­É½Õ¹…ÐÍÑ…ÉÑÕÀ€¡¹¼™¥ÉÍÐµµ•ÍÍ…”(€€€™É••é”¤¸()Ù•Éä…¹¡½È…ÍÍ•ÉÑÌ•á…Ñ±ä½¹”µ…Ñ €´…¹ä‘É¥™Ð™…¥±ÌÑ¡”‰Õ¥±)±½Õ‘±ä¸%‘•µÁ½Ñ•¹Ð€´Í…™”Ñ¼ÉÕ¸½¸•Ù•Éä$‰Õ¥±¸)UÍ…”èÁ…Ñ¡}ØÜØÀ¹Áä€ñ9=YÉ•Á¼É½½Ðø(ˆˆˆ)¥µÁ½ÉÐ½Ì)¥µÁ½ÉÐÍåÌ()I==P€ôÍåÌ¹…ÉÙlÅt¥˜±•¸¡ÍåÌ¹…ÉØ¤€ø€Ä•±Í”€ˆ¸ˆ)5€ô½Ì¹Á…Ñ ¹©½¥¸¡I==P°€‰…¹‘É½¥½…ÁÀ½ÍÉŒ½µ…¥¸½©…Ù„½½Éœ½¹½Ù„½5…¥¹Ñ¥Ù¥Ñä¹­Ðˆ¤)ÍÉŒ€ô½Á•¸¡5°•¹½‘¥¹œô‰ÕÑ˜´àˆ¤¹É•… ¤)¹}…ÁÁ±¥•€ô€À()¥˜€‰ØÜ¸Øˆ¥¸ÍÉŒè(€€€ÁÉ¥¹Ð ‰5…¥¹Ñ¥Ù¥Ñä¹­ÐèØÜ¸Ø¸ÀÁ…Ñ …±É•…‘ä…ÁÁ±¥•ˆ¤(€€€ÍåÌ¹•á¥Ð À¤()‘•˜É•À¡½±°¹•Ü°Ý¡…Ð¤è(€€€±½‰…°ÍÉŒ°¹}…ÁÁ±¥•(€€€¸€ôÍÉŒ¹½Õ¹Ð¡½±¤(€€€…ÍÍ•ÉÐ¸€ôô€Ä°€ˆ•Ìè…¹¡½È™½Õ¹€•‘à€¡•áÁ•Ñ•€Åà¤ˆ€”€¡Ý¡…Ð°¸¤(€€€ÍÉŒ€ôÍÉŒ¹É•Á±…”¡½±°¹•Ü¤(€€€¹}…ÁÁ±¥•€¬ô€Ä(€€€ÁÉ¥¹Ð ‰½¬€€•Ìˆ€”Ý¡…Ð¤((Œ€´´´´4Äè¥¹ÁÕÐ±•…É•½¹±äÝ¡•¸Ñ¡”µ•ÍÍ…”¥Ì…ÑÕ…±±ä½¹ÍÕµ•€´´´´)É•À¡Èœœœ€€€€€€€Ù…°Ñ•áÐ€ô¥¹ÁÕÐ¹Ñ•áÐ¹Ñ½MÑÉ¥¹œ ¤¹ÑÉ¥´ ¤(€€€€€€€¥˜€¡Ñ•áÐ¹¥ÍµÁÑä ¤¤É•ÑÕÉ¸(€€€€€€€¥¹ÁÕÐ¹Í•ÑQ•áÐ ˆˆ¤(€€€€€€€Ù…°¥Í¡¥À€ô!%A}AI=5AQL¹½¹Ñ…¥¹Ì¡Ñ•áÐ¤(€€€€€€€€¼¼ØÜ¸Ðè¹¼µµ½‘•°Ñ½½±Ì%IMP€´…±Õ±…Ñ½È…¹Á¡½¹”½µµ…¹‘ÌÝ½É¬(€€€€€€€€¼¼•Ù•¸‰•™½É”…¹äµ½‘•°¥Ì‘½Ý¹±½…‘•(€€€€€€€¥˜€¡Í½±Ù•É¥Ñ¡µ•Ñ¥Œ¡Ñ•áÐ¤¤É•ÑÕÉ¸(€€€€€€€¥˜€¡ÑÉåA¡½¹•½µµ…¹¡Ñ•áÐ¤¤É•ÑÕÉ¸(€€€€€€€¥˜€ …•¹ÍÕÉ•5½‘•±I•…‘ä ¤¤É•ÑÕÉ¸(€€€€€€€¥˜€¡½µÁ…Ñ¥¹œ¤ì(€€€€€€€€€€€Ñ½…ÍÐ ‰½µÁÉ•ÍÍ¥¹œ½±‘•Èµ•ÍÍ…•ÌƒŠP½¹”µ½µ•¹Ðˆ¤(€€€€€€€€€€€É•ÑÕÉ¸(€€€€€€€ô(œœœ°)Èœœœ€€€€€€€Ù…°Ñ•áÐ€ô¥¹ÁÕÐ¹Ñ•áÐ¹Ñ½MÑÉ¥¹œ ¤¹ÑÉ¥´ ¤(€€€€€€€¥˜€¡Ñ•áÐ¹¥ÍµÁÑä ¤¤É•ÑÕÉ¸(€€€€€€€Ù…°¥Í¡¥À€ô!%A}AI=5AQL¹½¹Ñ…¥¹Ì¡Ñ•áÐ¤(€€€€€€€€¼¼ØÜ¸Ðè¹¼µµ½‘•°Ñ½½±Ì%IMP€´…±Õ±…Ñ½È…¹Á¡½¹”½µµ…¹‘ÌÝ½É¬(€€€€€€€€¼¼•Ù•¸‰•™½É”…¹äµ½‘•°¥Ì‘½Ý¹±½…‘•(€€€€€€€¥˜€¡Í½±Ù•É¥Ñ¡µ•Ñ¥Œ¡Ñ•áÐ¤¤ì¥¹ÁÕÐ¹Í•ÑQ•áÐ ˆˆ¤ìÉ•ÑÕÉ¸ô(€€€€€€€¥˜€¡ÑÉåA¡½¹•½µµ…¹¡Ñ•áÐ¤¤ì¥¹ÁÕÐ¹Í•ÑQ•áÐ ˆˆ¤ìÉ•ÑÕÉ¸ô(€€€€€€€€¼¼ØÜ¸Øè­••ÀÑ¡”ÑåÁ•Ñ•áÐÝ¡•¸Ý”…É”9=PÁÉ½••‘¥¹œ€´¥ÐÝ…Ì(€€€€€€€€¼¼±•…É•¡•É”‰•™½É”°±½Í¥¹œµ•ÍÍ…•Ì‘ÕÉ¥¹œ½µÁ…Ñ¥½¸½ÈÝ¡•¸(€€€€€€€€¼¼¹¼µ½‘•°¥Ì±½…‘•å•Ð(€€€€€€€¥˜€ …•¹ÍÕÉ•5½‘•±I•…‘ä ¤¤É•ÑÕÉ¸(€€€€€€€¥˜€¡½µÁ…Ñ¥¹œ¤ì(€€€€€€€€€€€Ñ½…ÍÐ ‰½µÁÉ•ÍÍ¥¹œ½±‘•Èµ•ÍÍ…•ÌƒŠP½¹”µ½µ•¹Ðˆ¤(€€€€€€€€€€€É•ÑÕÉ¸(€€€€€€€ô(€€€€€€€¥¹ÁÕÐ¹Í•ÑQ•áÐ ˆˆ¤(œœœ°€‰4Ä¥¹ÁÕÐ±•…È½É‘•É¥¹œˆ¤((Œ€´´´´4ÈèÉ••Ñ¥¹œÉ•Í•ÑÌ„‘¥ÉÑä½¹Ñ•áÐ™¥ÉÍÐ€´´´´)É•À¡Èœœœ€€€€€€€€¼¼ØÜ¸ÌèÉ••Ñ¥¹Ì•Ð„±•…¸Ñ¥¹äÁÉ½µÁÐ€´¹¼¹½Ñ•Ì½Ý¥­¤½µ…Ñ¡Ì(€€€€€€€€¼¼ÝÉ…ÁÁ•È°Í¼Ñ¡”µ½‘•°¡…ÑÌ¥¹ÍÑ•…½˜ÍÕµµ…É¥é¥¹œ(€€€€€€€¥˜€¡M511Q1-}Ia`¹½¹Ñ…¥¹Í5…Ñ¡%¸¡Ñ•áÐ¤¤ì(€€€€€€€€€€€ÍÑ…ÉÑ•¹•É…Ñ¥½¸ (€€€€€€€€€€€€€€€€ˆ¡Q¡”ÕÍ•ÈÍ…¥è€œˆ€¬Ñ•áÐ€¬€ˆœ€´É••ÐÑ¡•´Ý…Éµ±ä¥¸½¹”½ÈÑÝ¼€ˆ€¬(€€€€€€€€€€€€€€€€€€€€‰Í¡½ÉÐÍ•¹Ñ•¹•Ì…¹½™™•ÈÑ¼¡•±À¸¼¹½Ðµ•¹Ñ¥½¸¹½Ñ•Ì°‘½Õµ•¹ÑÌ°€ˆ€¬(€€€€€€€€€€€€€€€€€€€€‰]¥­¥Á•‘¥„½ÈÍÕµµ…É¥•Ì¸¤ˆ°Ñ•áÐ°Á±…¥¸€ôÑÉÕ”¤(€€€€€€€€€€€É•ÑÕÉ¸(€€€€€€€ô(œœœ°)Èœœœ€€€€€€€€¼¼ØÜ¸ÌèÉ••Ñ¥¹Ì•Ð„±•…¸Ñ¥¹äÁÉ½µÁÐ€´¹¼¹½Ñ•Ì½Ý¥­¤½µ…Ñ¡Ì(€€€€€€€€¼¼ÝÉ…ÁÁ•È°Í¼Ñ¡”µ½‘•°¡…ÑÌ¥¹ÍÑ•…½˜ÍÕµµ…É¥é¥¹œ(€€€€€€€¥˜€¡M511Q1-}I`¹½¹Ñ…¥¹Í5…Ñ¡%¸¡Ñ•áÐ¤¤ì(€€€€€€€€€€€Ù…°É••ÑAÉ½µÁÐ€ô(€€€€€€€€€€€€€€€€ˆ¡Q¡”ÕÍ•ÈÍ…¥è€œˆ€¬Ñ•áÐ€¬€ˆœ€´É••ÐÑ¡•´Ý…Éµ±ä¥¸½¹”½ÈÑÝ¼€ˆ€¬(€€€€€€€€€€€€€€€€€€€€‰Í¡½ÉÐÍ•¹Ñ•¹•Ì…¹½™™•ÈÑ¼¡•±À¸¼¹½Ðµ•¹Ñ¥½¸¹½Ñ•Ì°‘½Õµ•¹ÑÌ°€ˆ€¬(€€€€€€€€€€€€€€€€€‰]¥­¥Á•‘¥„½ÈÍÕµµ…É¥•Ì¸¤ˆ(€€€€€€€€€€€€¼¼ØÜ¸Øè„É••Ñ¥¹œÍ•¹Ð¥¹Ñ¼„‘¥ÉÑä½ÍÑ…±”½¹Ñ•áÐµ…‘”Ñ¡”µ½‘•°(€€€€€€€€€€€€¼¼•¡¼½±ÍÑÉ¥Ðµµ½‘”‰½¥±•ÉÁ±…Ñ”€ ‰É½´•¹•É…°­¹½Ý±•‘”¸¸¸ˆ¤(€€€€€€€€€€€€¼¼¥¹ÍÑ•…½˜Í…å¥¹œ¡¤¸I•Í•Ð™¥ÉÍÐ°Ñ¡•¸É••Ð½¸„±•…¸•¹¥¹”¸(€€€€€€€€€€€¥˜€¡9½Ù…¹¥¹”¹½¹Ñ•áÑ¥ÉÑäñð¹••‘Í½¹Ñ•áÑ…ÉÉä¤ì(€€€€€€€€€€€€€€€¹••‘Í½¹Ñ•áÑ…ÉÉä€ô™…±Í”(€€€€€€€€€€€€€€€Í½Á”¹±…Õ¹ ì(€€€€€€€€€€€€€€€€€€€9½Ù…¹¥¹”¹É•Í•Ñ½¹Ù•ÉÍ…Ñ¥½¸¡Ñ¡¥Í5…¥¹Ñ¥Ù¥Ñä°Í•ÑÑ¥¹Ì¹ÍåÍÑ•µAÉ½µÁÐ¤(€€€€€€€€€€€€€€€€€€€ÍÑ…ÉÑ•¹•É…Ñ¥½¸¡É••ÑAÉ½µÁÐ°Ñ•áÐ°Á±…¥¸€ôÑÉÕ”¤(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô•±Í”ì(€€€€€€€€€€€€€€€ÍÑ…ÉÑ•¹•É…Ñ¥½¸¡É••ÑAÉ½µÁÐ°Ñ•áÐ°Á±…¥¸€ôÑÉÕ”¤(€€€€€€€€€€€ô(€€€€€€€€€€€É•ÑÕÉ¸(€€€€€€€ô(œœœ°€‰4ÈÉ••Ñ¥¹œ½¹Ñ•áÐÉ•Í•Ðˆ¤((Œ€´´´´4Ìè1…Q•`¥¹ÍÑÉÕÑ¥½¸½¹±ä™½ÈÉ•…°ÕÍ•ÈÅÕ•ÍÑ¥½¹Ì€´´´´)É•À¡Èœœœ€€€€€€€€€€€€€€€Ù…°ÀÈ€ô¥˜€¡Á±…¥¸¤ÁÉ½µÁÐ•±Í”•™™•Ñ¥Ù•AÉ½µÁÐ¡ÁÉ½µÁÐ¤(€€€€€€€€€€€€9½Ù…¹¥¹”¹Í•¹¡¥˜€¡Á±…¥¸ñð€…±½½­Í5…Ñ¡ä¡ÕÍ•ÉQ•áÐ€üèÁÉ½µÁÐ¤¤ÀÈ•±Í”µ…Ñ¡AÉ½µÁÐ¡ÀÈ¤°(€€€€€€€€€€€€€€€€€€€Í•ÑÑ¥¹Ì¹ÁÉ•‘¥Ñ1•¹Ñ ¤(œœœ°)Èœœœ€€€€€€€€€€€€€€€€€€Ù…°ÀÈ€ô¥˜€¡Á±…¥¸¤ÁÉ½µÁÐ•±Í”•™™•Ñ¥Ù•AÉ½µÁÐ¡ÁÉ½µÁÐ¤(€€€€€€€€€€€€€€€€¼¼ØÜ¸Øè1…Q•`½¹±ä™½ÈÉ•…°ÕÍ•ÈÅÕ•ÍÑ¥½¹Ì€´¥¹Ñ•É¹…°ÁÉ½µÁÑÌ(€€€€€€€€€€€€€€€€¼¼€¡¡¥ÁÌ½½¹Ñ¥¹Õ”½É•ÑÉä¤½¹Ñ…¥¸‘¥¥ÑÌ…¹¥¹©•Ñ•¹½Ñ•Ì…É”(€€€€€€€€€€€€€€€€¼¼™Õ±°½˜‘…Ñ•Ì°Ý¡¥ µ…‘”•Ù•Éä½¹”½˜Ñ¡•´€‰µ…Ñ¡äˆ(€€€€€€€€€€€€€€€Ù…°Ý…¹Ñ5…Ñ €ô€…Á±…¥¸€˜˜ÕÍ•ÉQ•áÐ€„ô¹Õ±°€˜˜±½½­Í5…Ñ¡ä¡ÕÍ•ÉQ•áÐ„„¤(€€€€€€€€€€€€€€€9½Ù…¹¥¹”¹Í•¹¡¥˜€¡Ý…¹Ñ5…Ñ ¤µ…Ñ¡AÉ½µÁÐ¡ÀÈ¤•±Í”ÀÈ°(€€€€€€€€€€€€€€€€€€€Í•ÑÑ¥¹Ì¹ÁÉ•‘¥Ñ1•¹Ñ ¤(œœœ°€‰4Ì±½½­Í5…Ñ¡äÕÍ•ÉQ•áÐ½¹±äˆ¤((Œ€´´´´4Ðè¹½Ñ•ÌÉ•±•Ù…¹”…Ñ”€´´´´)É•À¡Èœœœ€€€€€€€€€€€¡¥ÑÌ€ô-¹½Ý±•‘”¹Í•…É ¡Ñ¡¥Ì°Ñ•áÐ¤(œœœ°)Èœœœ€€€€€€€€€€€¡¥ÑÌ€ô-¹½Ý±•‘”¹Í•…É ¡Ñ¡¥Ì°Ñ•áÐ¤(€€€€€€€€€€€€¼¼ØÜ¸ØèÉ•±•Ù…¹”…Ñ”€´½¹”Í¡…É•Ý½É€¡”¹œ¸©ÕÍÐ€‰‰½Í”ˆ¤(€€€€€€€€€€€€¼¼µ…Ñ¡•©Õ¹¬¹½Ñ•Ì…¹Ñ¡”µ½‘•°…¹ÍÝ•É•™É½´Ñ¡•´Ý¥Ñ „(€€€€€€€€€€€€¼¼½¹™¥‘•¹Ðµ±½½­¥¹œ¥Ñ…Ñ¥½¸¸I•ÅÕ¥É”Ñ¡”Í¥¹¥™¥…¹Ð(€€€€€€€€€€€€¼¼Ñ•ÉµÌÑ¼…ÑÕ…±±ä…ÁÁ•…È¥¸Ñ¡”µ…Ñ¡•¡Õ¹­Ì¸(€€€€€€€€€€€¥˜€¡¡¥ÑÌ¹¥Í9½ÑµÁÑä ¤¤ì(€€€€€€€€€€€€€€€Ù…°Í¥Q•ÉµÌ€ô-¹½Ý±•‘”¹Ñ½­•¹¥é”¡Ñ•áÐ¤¹™¥±Ñ•Èì¥Ð¹±•¹Ñ €ø€Ìô¹‘¥ÍÑ¥¹Ð ¤(€€€€€€€€€€€€€€€Ù…°¡¥ÑQ•áÐ€ô¡¥ÑÌ¹©½¥¹Q½MÑÉ¥¹œ ˆ€ˆ¤ì €´ø ¹Ñ•áÐô¹±½Ý•É…Í” ¤(€€€€€€€€€€€€€€€Ù…°µ…Ñ¡•€ôÍ¥Q•ÉµÌ¹½Õ¹Ðì¡¥ÑQ•áÐ¹½¹Ñ…¥¹Ì¡¥Ð¤ô(€€€€€€€€€€€€€€€¥˜€¡µ…Ñ¡•€ôô€Àñð€¡Í¥Q•ÉµÌ¹Í¥é”€øô€È€˜˜µ…Ñ¡•€ð€È¤¤ì(€€€€€€€€€€€€€€€€€€€¡¥ÑÌ€ô•µÁÑå1¥ÍÐ ¤(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô(œœœ°€‰4Ð¹½Ñ•ÌÉ•±•Ù…¹”…Ñ”ˆ¤((Œ€´´´´4Õ„è±•…¸µ½‘•°µ•¡½•‰½¥±•ÉÁ±…Ñ”‰•™½É”¥Ñ…Ñ¥½¸€´´´´)É•À¡Èœœœ€€€€€€€€€€€€€€€€€€€€¼¼ØÔ¸Ðè…ÁÁ•¹Ñ¡”Í½ÕÉ”¥Ñ…Ñ¥½¸°Ñ¡•¸…¡”Ñ¡”…¹ÍÝ•È(œœœ°)Èœœœ€€€€€€€€€€€€€€€€€€€€¼¼ØÜ¸ØèÍµ…±°µ½‘•±Ì½ÁäÑ¡”ÍÑÉ¥Ðµµ½‘”µ…É­•È¥¹Ñ¼(€€€€€€€€€€€€€€€€€€€€¼¼É•Á±ä…™Ñ•ÈÉ•Á±ä…¹…¹¹½Õ¹”€‰Q¡”™¥¹…°…¹ÍÝ•È¥Ìèˆ€´(€€€€€€€€€€€€€€€€€€€€¼¼­••ÀÑ¡”™¥ÉÍÐµ…É­•È°‘É½ÀÑ¡”É•ÍÐ…¹Ñ¡”…¹¹½Õ¹•µ•¹ÑÌ(€€€€€€€€€€€€€€€€€€€Ù…°±•…¹•€ô±•…¹I•Á±åQ•áÐ¡É•Á±å5Íœ¹Ñ•áÐ¤(€€€€€€€€€€€€€€€€€€€¥˜€¡±•…¹•€„ôÉ•Á±å5Íœ¹Ñ•áÐ¤ì(€€€€€€€€€€€€€€€€€€€€€€€É•Á±å5Íœ¹Ñ•áÐ€ô±•…¹•(€€€€€€€€€€€€€€€€€€€€€€€…‘…ÁÑ•È¹Í•Ñ1…ÍÑQ•áÐ¡±•…¹•¤(€€€€€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€€€€€€¼¼ØÔ¸Ðè…ÁÁ•¹Ñ¡”Í½ÕÉ”¥Ñ…Ñ¥½¸°Ñ¡•¸…¡”Ñ¡”…¹ÍÝ•È(œœœ°€‰4Õ„±•…¹I•Á±åQ•áÐ…±°ˆ¤((Œ€´´´´4ÕˆèÑ¡”¡•±Á•È°…™Ñ•È±½½­Í5…Ñ¡ä€´´´´)É•À¡Èœœœ€€€€¼¨¨ØÜ¸Ìè‘½•ÌÑ¡¥Ìµ•ÍÍ…”…ÑÕ…±±ä¥¹Ù½±Ù”µ…Ñ¡Ìü%˜¹½Ð°Ñ¡”1…Q•`(€€€€€¨€¥¹ÍÑÉÕÑ¥½¸¥ÌÍ­¥ÁÁ•€´©½­•Ì…¹É••Ñ¥¹ÌÍÑ½À½µ¥¹œ½ÕÐ¥¸(€€€€€¨€‰½á•¹½Ñ…Ñ¥½¸¸€¨¼(€€€ÁÉ¥Ù…Ñ”™Õ¸±½½­Í5…Ñ¡ä¡ÀèMÑÉ¥¹œ¤è	½½±•…¸€ô(€€€€€€€À¹…¹äì¥Ð¹¥Í¥¥Ð ¤ôñð(œœœ°)Èœœœ€€€€¼¨¨ØÜ¸ØèÍÑÉ¥ÁÌµ½‘•°µ•¡½•‰½¥±•ÉÁ±…Ñ”€´É•Á•…Ñ•(€€€€€¨€µ…É­•ÉÌ€¡­••À½¹±äÑ¡”™¥ÉÍÐ¤…¹€‰Q¡”™¥¹…°…¹ÍÝ•È¥Ìèˆ±¥¹•Ì¸€¨¼(€€€ÁÉ¥Ù…Ñ”™Õ¸±•…¹I•Á±åQ•áÐ¡ÌèMÑÉ¥¹œ¤èMÑÉ¥¹œì(€€€€€€€Ù…°½ÕÐ€ôÉÉ…å1¥ÍÐñMÑÉ¥¹œø ¤(€€€€€€€Ù…ÈÍ••¹5…É­•È€ô™…±Í”(€€€€€€€™½È€¡É…Ü¥¸Ì¹±¥¹•Ì ¤¤ì(€€€€€€€€€€€Ù…È±¥¹”€ôÉ…Ü(€€€€€€€€€€€Ù…°±Ð€ô±¥¹”¹ÑÉ¥´ ¤(€€€€€€€€€€€¥˜€¡I••à ˆ ý¤¥yÑ¡”™¥¹…°…¹ÍÝ•È€ˆ¤¹½¹Ñ…¥¹Í5…Ñ¡%¸¡±Ð¤¤ì(€€€€€€€€€€€€€€€Ù…°¤€ô±Ð¹¥¹‘•á=˜ œèœ¤(€€€€€€€€€€€€€€€¥˜€¡¤€øô€À¤ì(€€€€€€€€€€€€€€€€€€€€¼¼€‰Q¡”™¥¹…°…¹ÍÝ•È¥Ìè`¸¸¸ˆ€´ø­••À½¹±äÑ¡”½¹Ñ•¹Ð(€€€€€€€€€€€€€€€€€€€±¥¹”€ô±¥¹”¹ÍÕ‰ÍÑÉ¥¹œ¡±¥¹”¹¥¹‘•á=˜ œèœ¤€¬€Ä¤¹ÑÉ¥µMÑ…ÉÐ ¤(€€€€€€€€€€€€€€€ô•±Í”¥˜€¡±Ð¹±•¹Ñ €ð€äÀ¤ì(€€€€€€€€€€€€€€€€€€€½¹Ñ¥¹Õ”€€€¼¼ÁÕÉ”…¹¹½Õ¹•µ•¹Ð±¥¹”€´‘É½À¥Ð(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô(€€€€€€€€€€€€€€€Ù…°µ…É­•È€ôI••à ˆ ý¤¥yqqÌ©É½´•¹•É…°­¹½Ý±•‘”qp¡¹½Ð¥¸å½ÕÈ¹½Ñ•Ìýqp¥qqÌ©lï¾š]?\\s*")
                if (marker.containsMatchIn(line)) {
                    if (seenMarker) {
                        line = marker.replace(line, "")
                } else {
                        seenMarker = true
                }
                }
                out.add(line)
        }
        return out.joinToString("\n").trim()
    }

    /** v7.3: does this message actually involve maths? If not, the LaTeX
     *   instruction is skipped - jokes and greetings stop coming out in
     *   boxed notation. */
    private fun looksMathy(p: String): Boolean =
        p.any { it.isDigit() } ||
''', "M5b cleanReplyText helper")

# ---- M6: re-check after the post-reply save suspension ----
rep(r'''                    // speak whatever is left of the reply
                    if (!speechCancelled) {
                        try { speakNewSentences(stripThinking(replyMsg.text), flush = true) }
                        catch (e: Exception) { }
                    }
''',
r'''                    // v7.6: the save above SUSPENDS the Main thread - the
                    // user can switch chats during it. Re-check before
                    // continuing/compacting, or the continuation lands in
                    // (or crashes on) the wrong chat
                    if (currentChat !== genChat) return@withContext
                    // speak whatever is left of the reply
                    if (!speechCancelled) {
                        try { speakNewSentences(stripThinking(replyMsg.text), flush = true) }
                        catch (e: Exception) { }
                    }
''', "M6 post-save chat re-check")

# ---- M7: openChat resets the attached document ----
rep(r'''        currentChat = chat
        settings.currentChatId = chat.id
        if (NovaEngine.isModelLoaded) NovaEngine.resetConversationAsync(this, settings.systemPrompt)
''',
r'''        currentChat = chat
        settings.currentChatId = chat.id
        // v7.6: the previously attached document leaked into the opened chat
        docName = null; docContext = null; docInjected = false
        if (NovaEngine.isModelLoaded) NovaEngine.resetConversationAsync(this, settings.systemPrompt)
''', "M7 openChat doc reset")

# ---- M8a: compactOldTurns remembers its chat ----
rep(r'''        compacting = true
        toast("Compressing older messages to keep replies fastâ€¦")
        scope.launch {
            val old = currentChat.messages.dropLast(6)
''',
r'''        compacting = true
        toast("Compressing older messages to keep replies fastâ€¦")
        scope.launch {
            // v7.6: remember which chat this compaction belongs to
            val chatAtStart = currentChat
            val old = currentChat.messages.dropLast(6)
''', "M8a compact chat capture")

# ---- M8b: ...and only writes into that chat ----
rep(r'''                 val summary = stripThinking(sb.toString()).trim()
                    if (summary.length > 40) {
                        compactSummary = summary
''',
r'''                val summary = stripThinking(sb.toString()).trim()
                // v7.6: user switched chats while the summary was generating -
                // never write the old chat's summary into the new one
                if (summary.length > 40 && currentChat === chatAtStart) {
                    compactSummary = summary
''', "M8b compact chat guard")

# ---- M9: tok/s counts only this segment ----
rep(r'''                        val genChars = stripThinking(replyMsg.text).length
''',
r'''                        // v7.6: count only what THIS segment wrote -
                        // continuations used the whole bubble and read ~2x
                        val segStart = if (newBubble) 0 else junction.coerceAtMost(replyMsg.text.length)
                        val genChars = stripThinking(replyMsg.text.substring(segStart)).length
''', "M9 tok/s segment")

# ---- M10: "tonight at 9:30" is pm too ----
rep(r'''            val pm = if (Regex("(?i)am|pm|:").containsMatchIn(clock.value)) ""
                else if (s.contains("tonight")) " pm" else ""
''',
r'''            val pm = if (Regex("(?i)am|pm").containsMatchIn(clock.value)) ""
                else if (s.contains("tonight")) " pm" else ""
''', "M10 tonight colon")

# ---- M11: warm the notes cache at startup ----
rep(r'''        if (WikiCore.isReady(this)) scope.launch(Dispatchers.IO) {
            WikiCore.warmUp(this@MainActivity)
        }
''',
r'''        if (WikiCore.isReady(this)) scope.launch(Dispatchers.IO) {
            WikiCore.warmUp(this@MainActivity)
        }
        // v7.6: warm the notes cache too - the first message of every
        // session otherwise parsed knowledge.json on the main thread
        scope.launch(Dispatchers.IO) { Knowledge.warmUp(this@MainActivity) }
''', "M11 Knowledge warmUp")

# ---- M12: bounded read for shared plain documents ----
rep(r'''    private fun readPlainDocument(uri: Uri): String {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        } catch (e: Exception) { return "" }
''',
r'''    private fun readPlainDocument(uri: Uri): String {
        // v7.6: cap the read at 2 MB - readBytes() on a huge shared
        // text file loaded it all into RAM before any limit applied
        val bytes = try {
            contentResolver.openInputStream()?.use { ins ->
                val cap = 2 * 1024 * 1024
                val buf = java.io.ByteArrayOutputStream(64 * 1024)
                val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                         if (buf.size() >= cap) break
                    }
                    buf.toByteArray()
            } ?: return ""
        } catch (e: Exception) { return "" }
''', "M12 bounded doc read")

# ---- M13: TTS read-aloud survives an utterance error ----
rep(r'''            override fun onStart(id: String?) { }
            override fun onError(id: String?) { }
            override fun onDone(id: String?) {
                if (id?.startsWith("doc") == true) speakNext()
            }
''',
r'''            override fun onStart(id: String?) { }
            // v7.6: one failed utterance used to stall the whole read
            override fun onError(id: String?) {
                if (id?.startsWith("doc") == true) speakNext()
            }
            override fun onDone(id: String?) {
                if (id?.startsWith("doc") == true) speakNext()
            }
''', "M13 TTS onError advance")

# ---- M14: phone commands understand ALL-CAPS ----
rep(r'''        val toks = t2.split(Regex("[^a-z0-9]+"))).filter { it.isNotEmpty() }
''',
r'''        val toks = t2.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
''', "M14 lowercase phone tokens")

# ---- M15: shared text is not silently dropped ----
rep(r'''        if (generating) return
        val preview = if (shared.length > 280) shared.take(280) + "â€¦" else shared
''',
r'''        // v7.6: tell the user instead of silently dropping the share
        if (generating) { toast("A reply is still running - share again when it ends"); return }
        val preview = if (shared.length > 280) shared.take(280) + "â€¦" else shared
''', "M15 shared text toast")

# ---- M16: OCR recognizer closed on failure too ----
rep(r'''                .addOnFailureListener { toast("Couldn't read image: " + (it.message ?: "error")) }
''',
r'''                .addOnFailureListener {
                    try { rec.close() } catch (e: Exception) { }
                    toast("Couldn't read image: " + (it.message ?: "error"))
                }
''', "M16 ocr failure close")

# ---- M17: runJs WebView destroyed with the dialog ----
old17 = '        val html = "<html><body><script>try{' + chr(92) + 'n" + code + "' + chr(92) + 'n}catch(e){console.log(' + chr(39) + 'Error: ' + chr(39) + '+e.message)}</script></body></html>"' + chr(10)
new17 = '        // v7.6: the WebView leaked on every run - destroy it with the dialog' + chr(10) + '        dlg.setOnDismissListener { try { wv.destroy() } catch (e: Exception) { } }' + chr(10) + old17
rep(old17, new17, "M17 runJs WebView destroy")

open(MA, "w", encoding="utf-8").write(src)
print("MainActivity.kt: v7.6.0 reliability + quality pack applied (%d edits)" % n_applied)
