package com.fusioncareer.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor createInterceptors() {
        MybatisPlusInterceptor createInterceptor = new MybatisPlusInterceptor();
        createInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        PaginationInnerInterceptor limitPagination = new PaginationInnerInterceptor(DbType.MYSQL);
        limitPagination.setMaxLimit(100L);
        createInterceptor.addInnerInterceptor(limitPagination);
        return createInterceptor;
    }
}
