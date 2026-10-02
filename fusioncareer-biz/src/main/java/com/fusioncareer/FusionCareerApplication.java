package com.fusioncareer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FusionCareer 应用启动类
 *
 * @author Xiong Heng
 */
@SpringBootApplication
@EnableScheduling
public class FusionCareerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FusionCareerApplication.class, args);
        System.out.println("=================================================");
        System.out.println("FusionCareer Backend Startup Complete (❁´◡`❁)");
        System.out.println("=================================================");
    }

}
