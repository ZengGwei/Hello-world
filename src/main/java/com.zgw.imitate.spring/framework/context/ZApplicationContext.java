package com.zgw.imitate.spring.framework.context;

import com.zgw.imitate.spring.framework.annotation.Autowried;
import com.zgw.imitate.spring.framework.annotation.Controller;
import com.zgw.imitate.spring.framework.annotation.Service;
import com.zgw.imitate.spring.framework.beans.BeanDefinition;
import com.zgw.imitate.spring.framework.beans.BeanPostProcessor;
import com.zgw.imitate.spring.framework.beans.BeanWrapper;
import com.zgw.imitate.spring.framework.context.support.BeanDefinitionReader;
import com.zgw.imitate.spring.framework.core.BeanFactory;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 〈〉*
 * Created by gw.Zeng on 2019/3/10
 */
public class ZApplicationContext implements BeanFactory {

    private String[] configLocation;

    private BeanDefinitionReader reader;

    //保存bean配置信息
    private Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<String, BeanDefinition>();

    //用来保证注册式单列
    private Map<String,Object> beanCacheMap = new HashMap<String, Object>();

    //用来存储被代理过的对象
    private Map<String,BeanWrapper> beanWrapperMap = new ConcurrentHashMap<String, BeanWrapper>();

    public ZApplicationContext(String ... locations) {
        this.configLocation = locations;
        this.refresh();
    }

    public  void refresh(){

        //定位
        this.reader =new BeanDefinitionReader(configLocation);

        //加载
        List<String> beanDefinitions = this.reader.loadBeanDefinitions();

        //注册
        doRegister(beanDefinitions);

        //依赖注入（lazy-init=false）执行依赖注入调用getBean().
        doAutowried();

    }

    //自动执行自动化依赖注入
    private  void doAutowried(){//todo 应用递归依赖
        for(Map.Entry<String,BeanDefinition> beanDefinitionEntry:this.beanDefinitionMap.entrySet()){
            String beanName = beanDefinitionEntry.getKey();
            if(!beanDefinitionEntry.getValue().isLazyInit()){
                Object bean = getBean(beanName);
            }
        }
        for(Map.Entry<String,BeanWrapper> beanWrapperEntry:this.beanWrapperMap.entrySet()){
            populateBean(beanWrapperEntry.getKey(),beanWrapperEntry.getValue().getOriginalInstance());
        }
    }

    public void populateBean(String beanName,Object instance){
        Class<?> clazz = instance.getClass();

        if(!(clazz.isAnnotationPresent(Controller.class)|| clazz.isAnnotationPresent(Service.class))){  return; }
        Field[] fields = clazz.getDeclaredFields();
        for (Field field:fields){
            if (!field.isAnnotationPresent(Autowried.class)){continue;}
            Autowried autowried = field.getAnnotation(Autowried.class);
            String autowriedBeanName = autowried.value().trim();
            if("".equals(autowriedBeanName)){
                autowriedBeanName = field.getType().getName();
            }
            field.setAccessible(true);
            try {
//                System.out.println("=======================" +instance +"," + autowriedBeanName + "," + this.beanWrapperMap.get(autowriedBeanName));

                field.set(instance,this.beanWrapperMap.get(autowriedBeanName).getWrappedInstance());
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }

        }
    }

    //将beaDefinition注册到beanDefinitionmap
    private void doRegister(List<String> beanDefinitions) {
      try {
          for (String className : beanDefinitions) {
              Class<?> beanClass = Class.forName(className);

              //beanName有三种情况：1.默认 2.自定义 3.接口注入

              if (beanClass.isInterface()){continue;}
              BeanDefinition beanDefinition = reader.registerBean(className);
              if (beanDefinition != null){
                  this.beanDefinitionMap.put(beanDefinition.getFactoryBeanName(),beanDefinition);
              }

              Class<?>[] interfaces = beanClass.getInterfaces();//获取接口
              for (Class<?> clazz :interfaces){
                  //如果多个实现类，只能覆盖/出错。可以自定义名字
                  this.beanDefinitionMap.put(clazz.getName(),beanDefinition);
              }

              //以上 容器初始化完成

          }
      }catch (Exception e){
          e.printStackTrace();
      }

    }


    //通过读取BeanDefinition中的信息，通过反射机制创建一个实例并返回
    //Spring 会用一个BeanWrapper来进行一次包装 返回对象            装饰器模式 1.保留原来的OOP关系 2.可扩展和增强(AOP)
    @Override//依赖注入从这个方法开始
    public Object getBean(String beanName) {
        BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
        String calssName = beanDefinition.getBeanClassName();
        try{
            //生成通知事件
            BeanPostProcessor beanPostProcessor = new BeanPostProcessor();

            Object instanceBean = instanceBean(beanDefinition);
            if (null == instanceBean){return null;}

            //在实例初始化前 调用一次
            beanPostProcessor.postProcessBeforeInitialization(instanceBean,beanName);

            BeanWrapper beanWrapper = new BeanWrapper(instanceBean);
            beanWrapper.setBeanPostProcessor(beanPostProcessor);
            this.beanWrapperMap.put(beanName,beanWrapper);

            //在实例初始化之后调用一次
            beanPostProcessor.postProcessAfterInitialization(instanceBean,beanName);

            //属性自动化注入
//            populateBean(beanName,instanceBean);

            return  this.beanWrapperMap.get(beanName).getWrappedInstance();//这样返回一个包装过的Bean,增大可操作空间
        }catch (Exception e){
            e.printStackTrace();
        }


        return null;
    }

    //传一个beanDefinition 返回一个bean实例
    private  Object instanceBean(BeanDefinition beanDefinition){
        Object instance = null;
        String beanClassName = beanDefinition.getBeanClassName();
        try {//TODO 考虑线程安全？？？
            if(this.beanCacheMap.containsKey(beanClassName)){
                instance =this.beanCacheMap.get(beanClassName);
            }else {
                Class<?> clazz = Class.forName(beanClassName);
                 instance = clazz.newInstance();
                this.beanCacheMap.put(beanClassName,instance);
            }
            return instance;
        }catch (Exception e){
            e.printStackTrace();
        }

        return null;
    }



    public String[] getBeanDefinitionNames() {
//        return getBeanFactory().getBeanDefinitionNames();//返回什么？？
        return this.beanDefinitionMap.keySet().toArray(new String[this.beanDefinitionMap.size()]);
    }

    public int getBeanDefinitionCount() {

//        return getBeanFactory().getBeanDefinitionCount();
        return this.beanDefinitionMap.size();
    }

    public Properties getConfig(){
        return this.reader.getConfig();
    }


}
