package top.mores.battlefield.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 配置解析里的人数一致性纠偏：总人数上限小于开局所需人数时，一局永远开不起来。
 */
class SectorConfigLoaderLimitsTest {

    @Test
    void maxPlayerNumberIsRaisedToMinPlayerNumber(@TempDir Path dir) throws IOException {
        // 线上测试配置的原样：maxPlayerNumber=1 却要求 2 人开局
        Files.writeString(dir.resolve("sectors.json"), """
                {
                  "arenas": [
                    {
                      "areaName": "area_alpha",
                      "maxPlayerNumber": 1,
                      "attackNumber": 1,
                      "defendNumber": 1,
                      "minPlayerNumber": 2
                    }
                  ]
                }
                """);

        SectorConfigLoader.ArenaConfig arena = SectorConfigLoader.loadConfig(dir).getArena("area_alpha");

        assertEquals(2, arena.minPlayerNumber);
        assertEquals(2, arena.maxPlayerNumber, "总人数上限必须至少等于开局所需人数");
    }

    @Test
    void normalConfigIsLeftAlone(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sectors.json"), """
                {
                  "arenas": [
                    {
                      "areaName": "area_alpha",
                      "maxPlayerNumber": 32,
                      "attackNumber": 16,
                      "defendNumber": 16,
                      "minPlayerNumber": 2
                    }
                  ]
                }
                """);

        SectorConfigLoader.ArenaConfig arena = SectorConfigLoader.loadConfig(dir).getArena("area_alpha");

        assertEquals(32, arena.maxPlayerNumber);
    }
}
